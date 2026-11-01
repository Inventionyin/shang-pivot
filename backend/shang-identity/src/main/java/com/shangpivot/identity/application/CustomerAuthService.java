package com.shangpivot.identity.application;

import cn.hutool.jwt.JWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.common.util.JwtUtil;
import com.shangpivot.common.util.TokenBlacklist;
import com.shangpivot.identity.dto.CustomerLoginRequest;
import com.shangpivot.identity.entity.Customer;
import com.shangpivot.identity.mapper.CustomerMapper;
import com.shangpivot.identity.vo.CustomerLoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * C 端顾客认证：短信验证码（演示可固定 123456）、自动注册、签发 web/h5 JWT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAuthService {

    public static final String DEMO_CODE = "123456";
    private static final String SMS_KEY = "sms:phone:";
    private static final String SMS_COOLDOWN_KEY = "sms:cooldown:";
    private static final Duration SMS_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final Set<String> CLIENT_TYPES = Set.of("web", "h5");

    private final CustomerMapper customerMapper;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;

    public Map<String, Object> sendSms(String phone) {
        String cooldownKey = SMS_COOLDOWN_KEY + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            Long ttl = redisTemplate.getExpire(cooldownKey);
            throw new BizException(ErrorCode.BAD_REQUEST, "发送过于频繁，请" + (ttl == null || ttl < 0 ? 60 : ttl) + "秒后再试");
        }
        redisTemplate.opsForValue().set(SMS_KEY + phone, DEMO_CODE, SMS_TTL);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_TTL);
        log.info("SMS code sent (demo) phone={}", maskPhone(phone));
        return Map.of("cooldownSec", 60);
    }

    @Transactional
    public CustomerLoginVO login(CustomerLoginRequest req) {
        String clientType = req.getClientType() == null ? "" : req.getClientType().trim().toLowerCase();
        if (!CLIENT_TYPES.contains(clientType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "clientType 须为 web 或 h5");
        }
        req.setClientType(clientType);
        String cached = redisTemplate.opsForValue().get(SMS_KEY + req.getPhone());
        if (!DEMO_CODE.equals(req.getCode()) && (cached == null || !cached.equals(req.getCode()))) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "验证码错误或已过期");
        }
        redisTemplate.delete(SMS_KEY + req.getPhone());

        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getPhone, req.getPhone()));
        if (customer == null) {
            customer = new Customer();
            customer.setPhone(req.getPhone());
            customer.setNickname("用户" + req.getPhone().substring(7));
            customer.setGender(0);
            customer.setStatus("NORMAL");
            customerMapper.insert(customer);
            log.info("Customer auto-registered id={} phone={}", customer.getId(), maskPhone(req.getPhone()));
        }
        if ("FROZEN".equals(customer.getStatus())) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        customer.setLastLoginAt(OffsetDateTime.now());
        customerMapper.updateById(customer);

        String access = jwtUtil.createAccessToken(customer.getId(), customer.getPhone(), clientType);
        String refresh = jwtUtil.createRefreshToken(customer.getId(), customer.getPhone(), clientType);
        return CustomerLoginVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(jwtUtil.getAccessTtlSeconds())
                .customer(toInfo(customer))
                .build();
    }

    public CustomerLoginVO refresh(String refreshToken) {
        JWT jwt = jwtUtil.parseAndValidate(refreshToken);
        if (!"refresh".equals(jwtUtil.getTokenType(jwt))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        String clientType = jwtUtil.getClientType(jwt);
        if (!CLIENT_TYPES.contains(clientType)) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "非顾客令牌");
        }
        Long customerId = jwtUtil.getUserId(jwt);
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if ("FROZEN".equals(customer.getStatus())) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        String jti = jwtUtil.getJti(jwt);
        if (StringUtils.hasText(jti)) {
            tokenBlacklist.add(jti, jwtUtil.getRefreshTtlSeconds());
        }
        String access = jwtUtil.createAccessToken(customer.getId(), customer.getPhone(), clientType);
        String refresh = jwtUtil.createRefreshToken(customer.getId(), customer.getPhone(), clientType);
        return CustomerLoginVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(jwtUtil.getAccessTtlSeconds())
                .customer(toInfo(customer))
                .build();
    }

    public void logout(String authHeader) {
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return;
        }
        try {
            JWT jwt = jwtUtil.parseAndValidate(authHeader.substring(7));
            String jti = jwtUtil.getJti(jwt);
            if (StringUtils.hasText(jti)) {
                tokenBlacklist.add(jti, jwtUtil.getAccessTtlSeconds());
            }
        } catch (BizException ignored) {
            // already invalid
        }
    }

    public CustomerLoginVO.CustomerInfo me() {
        Long id = SecurityContexts.requireUserId();
        Customer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if ("FROZEN".equals(customer.getStatus())) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        return toInfo(customer);
    }

    @Transactional
    public CustomerLoginVO.CustomerInfo updateMe(String nickname, String avatar) {
        Long id = SecurityContexts.requireUserId();
        Customer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (StringUtils.hasText(nickname)) {
            customer.setNickname(nickname.trim());
        }
        if (avatar != null) {
            customer.setAvatar(avatar);
        }
        customerMapper.updateById(customer);
        return toInfo(customer);
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private CustomerLoginVO.CustomerInfo toInfo(Customer c) {
        return CustomerLoginVO.CustomerInfo.builder()
                .id(c.getId())
                .phone(maskPhone(c.getPhone()))
                .nickname(c.getNickname())
                .avatar(c.getAvatar())
                .status(c.getStatus())
                .build();
    }
}
