package com.shangpivot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.AfterSaleApi;
import com.shangpivot.commerce.api.CartMutationApi;
import com.shangpivot.commerce.api.OrderCommandApi;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.graph.AgentGraphOrchestrator;
import com.shangpivot.customerservice.agent.service.PendingActionService;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolRegistry;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.llm.LlmSettings;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import com.shangpivot.customerservice.service.ChatSessionService;
import com.shangpivot.customerservice.service.CsGuardRailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3.1 写操作安全矩阵（真实 PG + 真实 pending_action/ToolRegistry，commerce 用 mock）：
 * 未确认不写、确认一次、重复确认幂等、取消/过期不写、越权拒绝、缺参/越权工具拒绝。
 */
@SpringBootTest
class AgentWriteSafetyIntegrationTest {

    @Autowired
    private AgentGraphOrchestrator orchestrator;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private PendingActionMapper pendingActionMapper;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderQueryApi orderQueryApi;
    @MockitoBean
    private OrderCommandApi orderCommandApi;
    @MockitoBean
    private AfterSaleApi afterSaleApi;
    @MockitoBean
    private ProductQueryApi productQueryApi;
    @MockitoBean
    private ProductSkuApi productSkuApi;
    @MockitoBean
    private CartMutationApi cartMutationApi;
    @MockitoBean
    private KnowledgeSearchService knowledgeSearchService;
    @MockitoBean
    private CsGuardRailService guardRailService;
    @MockitoBean
    private LlmSettingsResolver settingsResolver;

    private ChatSession session;

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
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, trace_id VARCHAR(64))
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS customerservice.message (
                    id BIGINT PRIMARY KEY, session_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL, content_type VARCHAR(24) NOT NULL DEFAULT 'TEXT',
                    content TEXT, metadata JSONB, reasoning TEXT, tool_trace JSONB,
                    degraded BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, trace_id VARCHAR(64))
                """);
        for (String table : List.of("message", "chat_session", "pending_action", "tool_call",
                "llm_call", "agent_step", "agent_run")) {
            jdbcTemplate.update("DELETE FROM customerservice." + table);
        }

        LlmSettings settings = new LlmSettings();
        settings.setSystemPrompt("你是尚品汇客服");
        settings.setModel("test-model");
        when(settingsResolver.resolve()).thenReturn(settings);

        when(orderQueryApi.getTrace(eq(100L), eq("O20261107001"))).thenReturn(
                new OrderTraceDTO("O20261107001", "PENDING_PAY", "100", null, null, null, null, List.of()));
        when(orderQueryApi.getTrace(eq(100L), eq("O20261107002"))).thenReturn(
                new OrderTraceDTO("O20261107002", "PENDING_RECEIVE", "200", null, null, null, null, List.of()));
        when(orderQueryApi.getTrace(eq(100L), eq("O20261107003"))).thenReturn(
                new OrderTraceDTO("O20261107003", "COMPLETED", "300", null, null, null, null, List.of()));
        when(afterSaleApi.apply(anyLong(), anyString(), anyString(), anyString())).thenReturn(99L);

        session = chatSessionService.getOrCreateActive(100L, "h5");
    }

    @Test
    void unknownOrderDoesNotCreatePendingAction() {
        CapturingEmitter emitter = send("取消订单 O99999999999");

        assertTrue(emitter.answer().contains("未找到订单"));
        assertEquals(0, pendingCount());
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void cancelWithoutOrderNoAsksForOrderNo() {
        CapturingEmitter emitter = send("取消订单");

        assertTrue(emitter.answer().contains("订单号"));
        assertEquals(0, pendingCount());
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void highRiskWriteOnlyCreatesPlanBeforeConfirmation() {
        send("取消订单 O20261107001");

        assertEquals(1, pendingCount());
        PendingAction action = pendingAction();
        assertEquals(PendingActionService.PENDING, action.getStatus());
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void confirmExecutesCancelExactlyOnce() {
        send("取消订单 O20261107001");
        PendingAction action = pendingAction();

        PendingActionService.ConfirmOutcome first = pendingActionService.confirm(action.getId(), 100L, AgentName.ORDER);
        assertTrue(first.result().containsKey("status"));
        verify(orderCommandApi, times(1)).cancel(100L, "O20261107001");

        PendingActionService.ConfirmOutcome second = pendingActionService.confirm(action.getId(), 100L, AgentName.ORDER);
        assertTrue(second.idempotent());
        verify(orderCommandApi, times(1)).cancel(100L, "O20261107001");
    }

    @Test
    void cancelActionNeverExecutesWrite() {
        send("取消订单 O20261107001");
        PendingAction action = pendingAction();

        pendingActionService.cancel(action.getId(), 100L);

        assertEquals(PendingActionService.CANCELLED, pendingActionMapper.selectById(action.getId()).getStatus());
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void expiredActionIsRejected() {
        send("取消订单 O20261107001");
        PendingAction action = pendingAction();
        jdbcTemplate.update("UPDATE customerservice.pending_action SET expires_at = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1), action.getId());

        assertThrows(BizException.class, () ->
                pendingActionService.confirm(action.getId(), 100L, AgentName.ORDER));
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void confirmReceiveFlow() {
        send("确认收货 O20261107002");
        PendingAction action = pendingAction();
        assertEquals("confirmReceive", action.getToolName());

        pendingActionService.confirm(action.getId(), 100L, AgentName.ORDER);
        verify(orderCommandApi, times(1)).confirmReceive(100L, "O20261107002");
    }

    @Test
    void afterSaleFlowCarriesTypeAndReason() {
        send("我要退货 O20261107003");
        PendingAction action = pendingAction();
        assertEquals("createAfterSale", action.getToolName());
        assertEquals("RETURN_REFUND", action.getArgsJson().get("type"));

        pendingActionService.confirm(action.getId(), 100L, AgentName.AFTER_SALE);
        verify(afterSaleApi, times(1)).apply(eq(100L), eq("O20261107003"),
                eq("RETURN_REFUND"), anyString());
    }

    @Test
    void crossCustomerConfirmIsForbidden() {
        send("取消订单 O20261107001");
        PendingAction action = pendingAction();

        assertThrows(BizException.class, () ->
                pendingActionService.confirm(action.getId(), 999L, AgentName.ORDER));
        verify(orderCommandApi, never()).cancel(anyLong(), anyString());
    }

    @Test
    void toolRegistryRejectsWrongAgentAndMissingArgs() {
        ToolContext wrongAgent = new ToolContext(100L, session.getId(), 1L, "t",
                AgentName.SHOPPING_GUIDE, true, null);
        ToolResult forbidden = toolRegistry.execute(wrongAgent, "cancelOrder",
                Map.of("orderNo", "O20261107001"));
        assertFalse(forbidden.success());
        assertEquals("TOOL_FORBIDDEN", forbidden.errorCode());

        ToolContext afterSale = new ToolContext(100L, session.getId(), 1L, "t",
                AgentName.AFTER_SALE, true, null);
        ToolResult missing = toolRegistry.execute(afterSale, "createAfterSale",
                Map.of("orderNo", "O20261107003"));
        assertFalse(missing.success());
        assertEquals("INVALID_ARGS", missing.errorCode());
    }

    private CapturingEmitter send(String text) {
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), text);
        CapturingEmitter emitter = new CapturingEmitter(objectMapper);
        orchestrator.respond(emitter, session, user);
        return emitter;
    }

    private PendingAction pendingAction() {
        PendingAction action = pendingActionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PendingAction>()
                        .eq(PendingAction::getSessionId, session.getId())
                        .orderByDesc(PendingAction::getId)
                        .last("LIMIT 1"));
        assertNotNull(action);
        return action;
    }

    private int pendingCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customerservice.pending_action", Integer.class);
        return count == null ? 0 : count;
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final ObjectMapper mapper;
        private final StringBuilder answer = new StringBuilder();

        private CapturingEmitter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder raw = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String text) {
                    raw.append(text);
                } else {
                    try {
                        raw.append(mapper.writeValueAsString(data));
                    } catch (Exception e) {
                        raw.append(data);
                    }
                }
            }
            String event = null;
            StringBuilder payload = new StringBuilder();
            for (String line : raw.toString().split("\n", -1)) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    payload.append(line.substring(5).trim());
                } else if (line.isBlank() && event != null) {
                    handle(event, payload.toString());
                    event = null;
                    payload.setLength(0);
                }
            }
            if (event != null && !payload.isEmpty()) {
                handle(event, payload.toString());
            }
        }

        private void handle(String event, String payload) {
            if (payload.isEmpty()) {
                return;
            }
            try {
                JsonNode node = mapper.readTree(payload);
                if ("delta".equals(event)) {
                    answer.append(node.path("delta").asText(""));
                }
            } catch (Exception ignored) {
                // no-op
            }
        }

        @Override
        public void complete() {
            // no-op
        }

        String answer() {
            return answer.toString();
        }
    }
}
