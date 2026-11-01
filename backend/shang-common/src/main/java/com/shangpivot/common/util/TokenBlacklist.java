package com.shangpivot.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TokenBlacklist {
    private static final String PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void add(String jti, long ttlSeconds) {
        if (jti == null || ttlSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean contains(String jti) {
        if (jti == null) {
            return false;
        }
        Boolean exists = redisTemplate.hasKey(PREFIX + jti);
        return Boolean.TRUE.equals(exists);
    }
}
