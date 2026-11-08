package com.shangpivot.customerservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 会话空闲回收：30 分钟无新消息（用户或助手）自动结束。
 * 扫描间隔和阈值均可用配置覆盖，测试环境可设 1 分钟验证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionIdleScheduler {

    private final ChatSessionService chatSessionService;

    @Value("${chat.session.idle-timeout-minutes:30}")
    private long idleTimeoutMinutes;

    @Scheduled(fixedDelayString = "${chat.session.idle-scan-ms:60000}")
    public void sweep() {
        int ended = chatSessionService.endIdleSessions(
                OffsetDateTime.now(), Duration.ofMinutes(Math.max(1, idleTimeoutMinutes)));
        if (ended > 0) {
            log.info("[cs] idle sessions ended: {}", ended);
        }
    }
}
