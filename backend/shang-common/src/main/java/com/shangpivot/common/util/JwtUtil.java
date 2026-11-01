package com.shangpivot.common.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${shang.jwt.secret}")
    private String secret;

    @Value("${shang.jwt.access-ttl-seconds}")
    private long accessTtlSeconds;

    @Value("${shang.jwt.refresh-ttl-seconds}")
    private long refreshTtlSeconds;

    public String createAccessToken(Long userId, String username, String clientType) {
        return createToken(userId, username, clientType, "access", accessTtlSeconds);
    }

    public String createRefreshToken(Long userId, String username, String clientType) {
        return createToken(userId, username, clientType, "refresh", refreshTtlSeconds);
    }

    private String createToken(Long userId, String username, String clientType, String tokenType, long ttlSeconds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", userId);
        payload.put("uname", username);
        payload.put("clientType", clientType);
        payload.put("tokenType", tokenType);
        payload.put("jti", IdUtil.fastSimpleUUID());
        Date now = new Date();
        payload.put(JWT.ISSUED_AT, now);
        payload.put(JWT.EXPIRES_AT, DateUtil.offsetSecond(now, (int) ttlSeconds));
        return JWTUtil.createToken(payload, secret.getBytes(StandardCharsets.UTF_8));
    }

    public JWT parseAndValidate(String token) {
        if (StrUtil.isBlank(token)) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        JWT jwt = JWTUtil.parseToken(token);
        if (!jwt.setKey(secret.getBytes(StandardCharsets.UTF_8)).verify()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (Exception e) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        }
        return jwt;
    }

    public Long getUserId(JWT jwt) {
        return Long.valueOf(jwt.getPayload("uid").toString());
    }

    public String getUsername(JWT jwt) {
        return String.valueOf(jwt.getPayload("uname"));
    }

    public String getClientType(JWT jwt) {
        Object v = jwt.getPayload("clientType");
        return v == null ? null : v.toString();
    }

    public String getTokenType(JWT jwt) {
        Object v = jwt.getPayload("tokenType");
        return v == null ? null : v.toString();
    }

    public String getJti(JWT jwt) {
        Object v = jwt.getPayload("jti");
        return v == null ? null : v.toString();
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }
}
