package com.shangpivot;

import com.shangpivot.commerce.api.CartAddResultDTO;
import com.shangpivot.commerce.api.CartMutationApi;
import com.shangpivot.commerce.api.OrderCommandApi;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.AfterSaleApi;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.graph.AgentGraphOrchestrator;
import com.shangpivot.customerservice.agent.service.PendingActionService;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.llm.LlmSettings;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import com.shangpivot.customerservice.retrieval.RetrievalService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3.1 图编排全链路（真实 PG + 真实 Trace/PendingAction，外部 commerce/LLM 用 mock）。
 * 覆盖：导购检索 → 卡片/回答；取消订单 → pending_action → 确认 → 工具执行 → EXECUTED。
 */
@SpringBootTest
class AgentGraphIntegrationTest {

    @Autowired
    private AgentGraphOrchestrator orchestrator;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private PendingActionMapper pendingActionMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductQueryApi productQueryApi;
    @MockitoBean
    private ProductSkuApi productSkuApi;
    @MockitoBean
    private OrderQueryApi orderQueryApi;
    @MockitoBean
    private OrderCommandApi orderCommandApi;
    @MockitoBean
    private AfterSaleApi afterSaleApi;
    @MockitoBean
    private CartMutationApi cartMutationApi;
    @MockitoBean
    private CsGuardRailService guardRailService;
    @MockitoBean
    private LlmSettingsResolver settingsResolver;
    @MockitoBean
    private KnowledgeSearchService knowledgeSearchService;
    @MockitoBean
    private RetrievalService retrievalService;

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
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, trace_id VARCHAR(64),
                    client_key VARCHAR(64) NOT NULL DEFAULT '')
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

        ProductCardDTO card = new ProductCardDTO(1L, "Redmi K70", "12+256G", null,
                new BigDecimal("2299"), null, 100L);
        when(productQueryApi.getBySpuIds(any())).thenReturn(List.of(card));
        when(retrievalService.searchProducts(any(), anyInt(), any()))
                .thenReturn(List.of(new RetrievalService.ProductHit(card, 0.9, "lexical")));
        when(orderQueryApi.getTrace(eq(100L), eq("O20261107001"))).thenReturn(
                new OrderTraceDTO("O20261107001", "PENDING_PAY", "100", null, null, null, null, List.of()));

        ProductSkuDTO sku = new ProductSkuDTO(10L, 1L, "12+256G", Map.of("内存", "12G"),
                new BigDecimal("2299"), null, 50);
        when(productSkuApi.listEnabledBySpu(1L)).thenReturn(List.of(sku));
        when(cartMutationApi.addToCart(eq(100L), eq(10L), anyInt()))
                .thenReturn(new CartAddResultDTO(1L, 10L, 2));
        when(knowledgeSearchService.search(any(), anyInt())).thenReturn(List.of(
                new KnowledgeSearchService.KnowledgeHit(1L, "退换货政策", "签收后 7 天内可无理由退货", 0.9, "vector")));
    }

    @Test
    void presaleGraphPersistsTraceAndAnswer() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "推荐一个手机");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.eventNames().contains("cards"));
        assertTrue(emitter.eventNames().contains("done"));
        Integer runs = jdbcTemplate.queryForObject("SELECT count(*) FROM customerservice.agent_run", Integer.class);
        Integer tools = jdbcTemplate.queryForObject("SELECT count(*) FROM customerservice.tool_call", Integer.class);
        assertEquals(1, runs);
        assertTrue(tools != null && tools >= 1);
    }

    @Test
    void cancelOrderRequiresConfirmationThenExecutesOnce() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "取消订单 O20261107001");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.eventNames().contains("action"));
        PendingAction action = pendingActionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PendingAction>()
                        .eq(PendingAction::getSessionId, session.getId())
                        .eq(PendingAction::getStatus, PendingActionService.PENDING)
                        .last("LIMIT 1"));
        assertNotNull(action);

        PendingActionService.ConfirmOutcome outcome = pendingActionService.confirm(
                action.getId(), 100L, AgentName.ORDER);
        assertTrue(outcome.result().containsKey("status"));
        PendingAction executed = pendingActionMapper.selectById(action.getId());
        assertEquals(PendingActionService.EXECUTED, executed.getStatus());
        assertEquals("CLOSED", String.valueOf(executed.getResultJson().get("status")));

        // 重复确认不再调用写工具（幂等）
        PendingActionService.ConfirmOutcome again = pendingActionService.confirm(
                action.getId(), 100L, AgentName.ORDER);
        assertTrue(again.idempotent());
    }

    @Test
    void specsGraphCallsStockTool() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "Redmi 有什么规格，有货吗");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.doneJson().contains("getStock"));
        assertTrue(emitter.allText().contains("12+256G"));
        assertEquals(0, pendingActionCount());
    }

    @Test
    void addToCartQuantityTwoCreatesConfirmation() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage first = chatSessionService.saveUserMessage(session.getId(), "推荐 Redmi K70");
        orchestrator.respond(new CapturingEmitter(), session, first);

        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "来两台");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.allText().contains("请确认"));
        assertEquals(1, pendingActionCount());
        verify(cartMutationApi, never()).addToCart(eq(100L), eq(10L), anyInt());
    }

    @Test
    void policyQuestionUsesKnowledgeToolWithoutWrite() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "退货政策是什么");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.doneJson().contains("searchKnowledge"));
        assertTrue(emitter.allText().contains("7 天"));
        assertEquals(0, pendingActionCount());
    }

    @Test
    void cancelWithoutOrderNoAsksForOrderNo() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "取消订单");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.allText().contains("订单号"));
        assertEquals(0, pendingActionCount());
    }

    @Test
    void crossCustomerOrderIsNotFoundAndDoesNotCreateAction() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "取消订单 O99999999999");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.allText().contains("未找到订单"));
        assertEquals(0, pendingActionCount());
    }

    @Test
    void explicitHandoffRequestQueuesSessionWithoutToolCall() {
        ChatSession session = chatSessionService.getOrCreateActive(100L, "h5");
        CsMessage user = chatSessionService.saveUserMessage(session.getId(), "我要转人工");

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session, user);

        assertTrue(emitter.doneJson().contains("HANDOFF"));
        assertTrue(emitter.doneJson().contains("QUEUED"));
        assertEquals(0, pendingActionCount());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM customerservice.chat_session WHERE id = ?", String.class, session.getId());
        assertEquals("QUEUED", status);
    }

    private int pendingActionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customerservice.pending_action", Integer.class);
        return count == null ? 0 : count;
    }

    /** 捕获 SSE 事件名与 data，便于断言。 */
    private static final class CapturingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder line = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType data : builder.build()) {
                line.append(data.getData());
            }
            events.add(line.toString());
        }

        @Override
        public void complete() {
            // no-op
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (String event : events) {
                int idx = event.indexOf("event:");
                if (idx >= 0) {
                    int end = event.indexOf('\n', idx);
                    names.add(event.substring(idx + 6, end < 0 ? event.length() : end).trim());
                }
            }
            return names;
        }

        String doneJson() {
            return jsonFor("done");
        }

        String allText() {
            return String.join("\n", events);
        }

        private String jsonFor(String name) {
            for (String event : events) {
                if (event.contains("event:" + name)) {
                    return event;
                }
            }
            return "";
        }
    }
}
