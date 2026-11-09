package com.shangpivot.customerservice.service;

import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 智能客服对抗性护栏（P0）：
 * 1. 用户级对话限流（防 LLM/embedding 配额被脚本刷爆）；
 * 2. 会话级生成互斥锁（上一条还在流式生成时拒绝新消息，防止编排交错）。
 */
@Service
@RequiredArgsConstructor
public class CsGuardRailService {

    private static final String USER_RL_KEY = "chat:rl:user:";
    private static final String SESSION_LOCK_KEY = "chat:lock:session:";
    private static final String CREATE_LOCK_KEY = "chat:lock:create:";
    private static final int USER_RL_MAX = 20;
    private static final Duration USER_RL_WINDOW = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(90);
    private static final Duration CREATE_LOCK_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;

    /** 用户级对话限流：5 分钟窗口最多 20 条。 */
    public void checkChatRateLimit(Long customerId) {
        String key = USER_RL_KEY + customerId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, USER_RL_WINDOW);
        }
        if (count != null && count > USER_RL_MAX) {
            Long ttl = redisTemplate.getExpire(key);
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "发送太频繁啦，请" + (ttl == null || ttl < 0 ? "稍后" : Math.max(ttl, 1) + " 秒后") + "再试");
        }
    }

    /** 会话级互斥：上一条回复生成中则拒绝。 */
    public void lockSession(Long sessionId) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(SESSION_LOCK_KEY + sessionId, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(ok)) {
            throw new BizException(ErrorCode.CONFLICT, "上一条回复还在生成中，稍等它说完～");
        }
    }

    public void unlockSession(Long sessionId) {
        redisTemplate.delete(SESSION_LOCK_KEY + sessionId);
    }

    /**
     * 会话创建互斥：同一客户 + 客户端并发打开 /ai 时只允许一个请求执行“查进行中会话 → 没有则创建”，
     * 避免双击/多标签同时插入两条 AI_ONGOING。锁 5 秒自动过期，等待最多 1 秒。
     */
    public String lockSessionCreate(Long customerId, String clientKey) {
        String key = createKey(customerId, clientKey);
        String token = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, CREATE_LOCK_TTL);
            if (Boolean.TRUE.equals(ok)) {
                return token;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BizException(ErrorCode.CONFLICT, "会话正在初始化，请稍后重试");
    }

    public void unlockSessionCreate(Long customerId, String clientKey, String token) {
        if (token == null) {
            return;
        }
        try {
            String key = createKey(customerId, clientKey);
            if (token.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
            }
        } catch (Exception ignored) {
            // 锁有 TTL，Redis 短暂异常不阻塞主流程。
        }
    }

    private static String createKey(Long customerId, String clientKey) {
        return CREATE_LOCK_KEY + customerId + ":" + (clientKey == null ? "" : clientKey.trim());
    }
}
