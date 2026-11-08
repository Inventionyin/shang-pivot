package com.shangpivot;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.service.ChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话生命周期集成测试（真实 PostgreSQL）：
 * 普通会话复用、human 测试会话复用、ai 测试会话不复用、fresh 新建、
 * 历史消息/商品卡回放、越权、30 分钟空闲结束。
 */
@SpringBootTest
class ChatSessionLifecycleIntegrationTest {

    private static final long CUSTOMER_A = 9_900_000_000_000_001L;
    private static final long CUSTOMER_B = 9_900_000_000_000_002L;

    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private ChatSessionMapper sessionMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS customerservice");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS customerservice.chat_session (
                    id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL,
                    client_type VARCHAR(16) NOT NULL DEFAULT 'h5',
                    status VARCHAR(24) NOT NULL DEFAULT 'AI_ONGOING',
                    seat_id BIGINT, summary VARCHAR(255),
                    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ended_at TIMESTAMPTZ,
                    last_message_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, trace_id VARCHAR(64),
                    is_test BOOLEAN NOT NULL DEFAULT FALSE,
                    test_source VARCHAR(16) NOT NULL DEFAULT '',
                    test_run_id VARCHAR(64) NOT NULL DEFAULT '')
                """);
        jdbcTemplate.execute("ALTER TABLE customerservice.chat_session "
                + "ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ");
        jdbcTemplate.execute("ALTER TABLE customerservice.chat_session "
                + "ADD COLUMN IF NOT EXISTS is_test BOOLEAN NOT NULL DEFAULT FALSE");
        jdbcTemplate.execute("ALTER TABLE customerservice.chat_session "
                + "ADD COLUMN IF NOT EXISTS test_source VARCHAR(16) NOT NULL DEFAULT ''");
        jdbcTemplate.execute("ALTER TABLE customerservice.chat_session "
                + "ADD COLUMN IF NOT EXISTS test_run_id VARCHAR(64) NOT NULL DEFAULT ''");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS customerservice.message (
                    id BIGINT PRIMARY KEY, session_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL, content_type VARCHAR(24) NOT NULL DEFAULT 'TEXT',
                    content TEXT, metadata JSONB, reasoning TEXT, tool_trace JSONB,
                    degraded BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, trace_id VARCHAR(64))
                """);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM customerservice.message WHERE session_id IN "
                + "(SELECT id FROM customerservice.chat_session WHERE customer_id IN (?, ?))",
                CUSTOMER_A, CUSTOMER_B);
        jdbcTemplate.update("DELETE FROM customerservice.chat_session WHERE customer_id IN (?, ?)",
                CUSTOMER_A, CUSTOMER_B);
    }

    @Test
    void reusesNormalAndHumanSessionsButNotAiTestSessions() {
        ChatSession normal1 = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        ChatSession normal2 = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        assertEquals(normal1.getId(), normal2.getId(), "普通进行中会话应复用");

        ChatSession human1 = chatSessionService.getOrCreateActive(CUSTOMER_B, "h5", true, "human", "human-1");
        ChatSession human2 = chatSessionService.getOrCreateActive(CUSTOMER_B, "h5", true, "human", "human-2");
        assertEquals(human1.getId(), human2.getId(), "人工体验会话应复用");
        assertTrue(Boolean.TRUE.equals(human2.getIsTest()));
        assertEquals("human", human2.getTestSource());

        ChatSession ai1 = chatSessionService.getOrCreateActive(CUSTOMER_B, "h5", true, "ai", "run-1");
        ChatSession ai2 = chatSessionService.getOrCreateActive(CUSTOMER_B, "h5", true, "ai", "run-2");
        assertNotEquals(ai1.getId(), ai2.getId(), "平台 AI 自动测试应每次新建会话");
        assertEquals("ai", ai2.getTestSource());
    }

    @Test
    void startFreshEndsOldSessionAndCreatesNew() {
        ChatSession old = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        ChatSession fresh = chatSessionService.startFresh(CUSTOMER_A, "h5");
        assertNotEquals(old.getId(), fresh.getId());

        ChatSession ended = sessionMapper.selectById(old.getId());
        assertEquals(ChatSession.STATUS_ENDED, ended.getStatus());
        assertNotNull(ended.getEndedAt());
        assertEquals(ChatSession.STATUS_AI_ONGOING, fresh.getStatus());
    }

    @Test
    void historyReturnsMessagesAndProductCardsInChronologicalOrder() {
        ChatSession session = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        chatSessionService.saveUserMessage(session.getId(), "推荐手机");
        chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                "给你挑了两款", null,
                Map.of(CsMessage.META_ITEMS, List.of(Map.of("spuId", 10001, "name", "测试商品"))),
                null, false);

        List<Map<String, Object>> history = chatSessionService.history(session.getId(), 0L, 20);
        assertEquals(2, history.size());
        assertEquals(CsMessage.ROLE_ASSISTANT, history.get(0).get("role"));
        assertEquals(CsMessage.ROLE_USER, history.get(1).get("role"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) history.get(0).get("metadata");
        assertNotNull(metadata);
        assertTrue(metadata.get(CsMessage.META_ITEMS) instanceof List<?>);
    }

    @Test
    void rejectsAnotherCustomersSession() {
        ChatSession session = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        assertThrows(BizException.class, () -> chatSessionService.requireOwned(session.getId(), CUSTOMER_B));
    }

    @Test
    void endsIdleSessionsAndMessageTouchResetsTimer() {
        ChatSession stale = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        ChatSession recent = chatSessionService.getOrCreateActive(CUSTOMER_B, "h5");
        OffsetDateTime now = OffsetDateTime.now();
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, stale.getId())
                .set(ChatSession::getLastMessageAt, now.minusMinutes(31)));
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, recent.getId())
                .set(ChatSession::getLastMessageAt, now.minusMinutes(29)));

        int ended = chatSessionService.endIdleSessions(now, Duration.ofMinutes(30));
        assertTrue(ended >= 1);
        assertEquals(ChatSession.STATUS_ENDED, sessionMapper.selectById(stale.getId()).getStatus());
        assertEquals(ChatSession.STATUS_AI_ONGOING, sessionMapper.selectById(recent.getId()).getStatus());

        // 新消息会把 stale 会话的活跃时间刷新，后续扫描不能再误结束。
        ChatSession revived = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        chatSessionService.saveUserMessage(revived.getId(), "我还在");
        chatSessionService.endIdleSessions(now.plusMinutes(1), Duration.ofMinutes(30));
        assertEquals(ChatSession.STATUS_AI_ONGOING, sessionMapper.selectById(revived.getId()).getStatus());
    }

    @Test
    void idleSweepDoesNotTouchFreshSessions() {
        ChatSession session = chatSessionService.getOrCreateActive(CUSTOMER_A, "h5");
        int ended = chatSessionService.endIdleSessions(OffsetDateTime.now(), Duration.ofMinutes(30));
        assertFalse(ended > 0 && ChatSession.STATUS_ENDED.equals(
                sessionMapper.selectById(session.getId()).getStatus()));
    }
}
