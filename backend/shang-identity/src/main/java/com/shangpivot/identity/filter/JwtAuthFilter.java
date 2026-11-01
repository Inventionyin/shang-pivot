package com.shangpivot.identity.filter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import tools.jackson.databind.ObjectMapper;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.LoginUser;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.common.util.JwtUtil;
import com.shangpivot.common.util.TokenBlacklist;
import com.shangpivot.common.util.TraceIdUtil;
import com.shangpivot.identity.application.PermissionQueryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT 鉴权过滤器：按 clientType 区分 Admin 与顾客 Token，互不通用。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final PermissionQueryService permissionQueryService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            String header = request.getHeader("Authorization");
            if (StrUtil.isNotBlank(header) && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    JWT jwt = jwtUtil.parseAndValidate(token);
                    if (!"access".equals(jwtUtil.getTokenType(jwt))) {
                        writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
                        return;
                    }
                    String jti = jwtUtil.getJti(jwt);
                    if (tokenBlacklist.contains(jti)) {
                        writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
                        return;
                    }
                    String clientType = jwtUtil.getClientType(jwt);
                    boolean adminPath = path.startsWith("/admin");
                    boolean apiPath = path.startsWith("/api");
                    boolean isAdminToken = "admin".equals(clientType);
                    boolean isCustomerToken = "web".equals(clientType) || "h5".equals(clientType);

                    if (adminPath && !isAdminToken) {
                        writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
                        return;
                    }
                    if (apiPath && isAdminToken) {
                        writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
                        return;
                    }

                    Long userId = jwtUtil.getUserId(jwt);
                    Set<String> permissions = Collections.emptySet();
                    Set<String> roles = Collections.emptySet();
                    if (isAdminToken) {
                        permissions = permissionQueryService.listPermissionCodes(userId);
                        roles = permissionQueryService.listRoleCodes(userId);
                    }
                    LoginUser loginUser = LoginUser.builder()
                            .userId(userId)
                            .username(jwtUtil.getUsername(jwt))
                            .clientType(clientType)
                            .jti(jti)
                            .permissions(permissions)
                            .roles(roles)
                            .build();
                    SecurityContexts.set(loginUser);
                    TraceIdUtil.setUser(loginUser.getUserId(), loginUser.getUsername());
                    var authorities = permissions.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
                    if (isCustomerToken) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
                    }
                    var authentication = new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (com.shangpivot.common.exception.BizException e) {
                    // leave unauthenticated; Security will reject protected paths
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContexts.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Result.fail(code));
    }
}
