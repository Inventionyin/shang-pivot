package com.shangpivot.identity.application;

import cn.hutool.jwt.JWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.common.util.JwtUtil;
import com.shangpivot.common.util.TokenBlacklist;
import com.shangpivot.identity.dto.LoginRequest;
import com.shangpivot.identity.entity.AdminUser;
import com.shangpivot.identity.mapper.AdminUserMapper;
import com.shangpivot.identity.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 后台认证用例：登录锁定、JWT 签发/刷新/吊销，并组装角色·权限码·菜单树。
 * <p>失败计数键 login:fail:{username}，连续 5 次锁 15 分钟。
 * <p>关联表：admin_user；依赖 PermissionQueryService / TokenBlacklist。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String FAIL_KEY = "login:fail:";
    private static final int MAX_FAIL = 5;
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final StringRedisTemplate redisTemplate;
    private final PermissionQueryService permissionQueryService;
    private final OperationLogService operationLogService;

    /**
     * 账号密码登录：校验锁定与状态，签发 access/refresh，写入操作日志。
     */
    @Transactional
    public LoginVO login(LoginRequest request, String ip, String ua) {
        String failKey = FAIL_KEY + request.getUsername();
        String failCount = redisTemplate.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount) >= MAX_FAIL) {
            log.warn("Login locked username={} ip={}", request.getUsername(), ip);
            operationLogService.record("auth", "login",
                    Map.of("username", request.getUsername()), "FAIL",
                    null, request.getUsername(), ip, ua);
            throw new BizException(ErrorCode.ACCOUNT_LOCKED, "登录失败次数过多，请15分钟后再试");
        }

        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, LOCK_TTL);
            }
            log.warn("Login failed username={} ip={} failCount={}", request.getUsername(), ip, count);
            operationLogService.record("auth", "login",
                    Map.of("username", request.getUsername()), "FAIL",
                    null, request.getUsername(), ip, ua);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (!"ENABLED".equals(user.getStatus())) {
            log.warn("Login denied disabled user username={}", request.getUsername());
            operationLogService.record("auth", "login",
                    Map.of("username", request.getUsername()), "FAIL",
                    user.getId(), user.getUsername(), ip, ua);
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }

        redisTemplate.delete(failKey);
        user.setLastLoginAt(OffsetDateTime.now());
        adminUserMapper.updateById(user);

        String access = jwtUtil.createAccessToken(user.getId(), user.getUsername(), "admin");
        String refresh = jwtUtil.createRefreshToken(user.getId(), user.getUsername(), "admin");
        Set<String> roles = permissionQueryService.listRoleCodes(user.getId());
        Set<String> permissions = permissionQueryService.listPermissionCodes(user.getId());

        operationLogService.record("auth", "login",
                Map.of("username", user.getUsername()), "SUCCESS",
                user.getId(), user.getUsername(), ip, ua);
        log.info("Login success userId={} username={} roles={}", user.getId(), user.getUsername(), roles);

        return LoginVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(jwtUtil.getAccessTtlSeconds())
                .user(LoginVO.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .realName(user.getRealName())
                        .roles(roles)
                        .permissions(permissions)
                        .menus(permissionQueryService.buildMenuTree(user.getId()))
                        .build())
                .build();
    }

    /**
     * 登出：将 access token 的 jti 加入黑名单（解析失败则尽量从当前上下文记日志）。
     */
    public void logout(String accessToken) {
        Long userId = null;
        String username = null;
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                JWT jwt = jwtUtil.parseAndValidate(accessToken.startsWith("Bearer ")
                        ? accessToken.substring(7) : accessToken);
                tokenBlacklist.add(jwtUtil.getJti(jwt), jwtUtil.getAccessTtlSeconds());
                userId = jwtUtil.getUserId(jwt);
                username = jwtUtil.getUsername(jwt);
            } catch (Exception ignored) {
            }
        }
        if (userId == null) {
            var current = SecurityContexts.get();
            if (current != null) {
                userId = current.getUserId();
                username = current.getUsername();
            }
        }
        operationLogService.record("auth", "logout", null, "SUCCESS", userId, username, null, null);
    }

    /**
     * 用 refresh token 换发新 access/refresh，并吊销旧 refresh（jti 入黑名单）。
     */
    public LoginVO refresh(String refreshToken) {
        JWT jwt = jwtUtil.parseAndValidate(refreshToken);
        if (!"refresh".equals(jwtUtil.getTokenType(jwt))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if (tokenBlacklist.contains(jwtUtil.getJti(jwt))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        Long userId = jwtUtil.getUserId(jwt);
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        tokenBlacklist.add(jwtUtil.getJti(jwt), jwtUtil.getRefreshTtlSeconds());
        String access = jwtUtil.createAccessToken(user.getId(), user.getUsername(), "admin");
        String refresh = jwtUtil.createRefreshToken(user.getId(), user.getUsername(), "admin");
        return LoginVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(jwtUtil.getAccessTtlSeconds())
                .user(LoginVO.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .realName(user.getRealName())
                        .roles(permissionQueryService.listRoleCodes(userId))
                        .permissions(permissionQueryService.listPermissionCodes(userId))
                        .menus(permissionQueryService.buildMenuTree(userId))
                        .build())
                .build();
    }

    /** 返回当前登录用户的角色、权限码与可见菜单树。 */
    public LoginVO.UserInfo me() {
        Long userId = SecurityContexts.requireUserId();
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return LoginVO.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .roles(permissionQueryService.listRoleCodes(userId))
                .permissions(permissionQueryService.listPermissionCodes(userId))
                .menus(permissionQueryService.buildMenuTree(userId))
                .build();
    }
}
