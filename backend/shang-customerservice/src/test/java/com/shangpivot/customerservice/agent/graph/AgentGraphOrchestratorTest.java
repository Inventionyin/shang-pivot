package com.shangpivot.customerservice.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.customerservice.agent.GuardPipeline;
import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.service.PendingActionService;
import com.shangpivot.customerservice.agent.tool.ToolRegistry;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.trace.AgentTraceService;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.llm.LlmClient;
import com.shangpivot.customerservice.llm.LlmSettings;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import com.shangpivot.customerservice.service.ChatSessionService;
import com.shangpivot.customerservice.service.CsGuardRailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgentGraphOrchestratorTest {

    private final LlmClient llmClient = Mockito.mock(LlmClient.class);
    private final LlmSettingsResolver settingsResolver = Mockito.mock(LlmSettingsResolver.class);
    private final AgentPromptFactory promptFactory = Mockito.mock(AgentPromptFactory.class);
    private final SupervisorAgent supervisor = Mockito.mock(SupervisorAgent.class);
    private final ToolRegistry toolRegistry = Mockito.mock(ToolRegistry.class);
    private final PendingActionService pendingActionService = Mockito.mock(PendingActionService.class);
    private final AgentTraceService traceService = Mockito.mock(AgentTraceService.class);
    private final RunContextRegistry runContexts = new RunContextRegistry();
    private final ChatSessionService chatSessionService = Mockito.mock(ChatSessionService.class);
    private final CsGuardRailService guardRailService = Mockito.mock(CsGuardRailService.class);
    private final CsMessageMapper messageMapper = Mockito.mock(CsMessageMapper.class);
    private final ProductQueryApi productQueryApi = Mockito.mock(ProductQueryApi.class);
    private final ProductSkuApi productSkuApi = Mockito.mock(ProductSkuApi.class);
    private final RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
    private final GuardPipeline guardPipeline = Mockito.mock(GuardPipeline.class);

    private AgentGraphOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentGraphOrchestrator(llmClient, settingsResolver, promptFactory, supervisor,
                new HandoffPolicy(), toolRegistry, pendingActionService, traceService, runContexts,
                chatSessionService, guardRailService, messageMapper, productQueryApi, productSkuApi,
                retrievalService, guardPipeline, new ObjectMapper(),
                new org.bsc.langgraph4j.checkpoint.MemorySaver());
        orchestrator.init();

        AgentRun run = new AgentRun();
        run.setId(1L);
        LlmSettings settings = new LlmSettings();
        settings.setModel("test-model");
        when(settingsResolver.resolve()).thenReturn(settings);
        when(traceService.startRun(anyLong(), anyLong(), any(), any(), any(), any(), any())).thenReturn(run);
        when(traceService.startStep(anyLong(), any(), anyString(), any(), any())).thenReturn(10L);
        when(chatSessionService.saveAssistantMessage(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(message(99L));
        when(guardPipeline.filterOutput(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(guardPipeline.stripPlanningPrefix(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(llmClient.available()).thenReturn(false);
    }

    @Test
    void presaleRunsSearchToolThenAnswersWithCards() {
        when(supervisor.route(anyString())).thenReturn(new SupervisorAgent.RouteResult(
                new IntentRouter.Intent(IntentRouter.Type.PRESALE, "手机", null, "", 1, true),
                AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.SEARCH_PRODUCTS, false, false,
                null, "", 1));
        ProductCardDTO card = new ProductCardDTO(1L, "Redmi K70", "12+256G", null,
                new BigDecimal("2299"), null, 100L);
        when(toolRegistry.execute(any(), eq("searchProducts"), any()))
                .thenReturn(ToolResult.ok(List.of(new RetrievalService.ProductHit(card, 0.9, "lexical")),
                        "1 个商品"));

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session(), user("推荐一个手机"));

        assertTrue(emitter.eventNames().contains("state"));
        assertTrue(emitter.eventNames().contains("cards"));
        assertTrue(emitter.eventNames().contains("done"));
        assertTrue(emitter.cardsJson().contains("Redmi K70"));
        assertTrue(emitter.doneJson().contains("degraded"));
        assertTrue(emitter.doneJson().contains("false"), emitter.doneJson());
    }

    @Test
    void highRiskWriteCreatesPendingActionAndEmitsActionEvent() {
        when(supervisor.route(anyString())).thenReturn(new SupervisorAgent.RouteResult(
                new IntentRouter.Intent(IntentRouter.Type.ORDER, null, "O20261107001", "", 1, true),
                AgentName.ORDER, SupervisorAgent.Action.CANCEL_ORDER, true, false,
                "O20261107001", "", 1));
        when(toolRegistry.execute(any(), eq("getOrder"), any())).thenReturn(ToolResult.ok(
                new OrderTraceDTO("O20261107001", "PENDING_PAY", "100", null, null, null, null, List.of()),
                "订单"));
        PendingAction action = new PendingAction();
        action.setId(7L);
        action.setSessionId(1L);
        action.setCustomerId(100L);
        action.setToolName("cancelOrder");
        action.setArgsJson(Map.of("orderNo", "O20261107001"));
        action.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        when(pendingActionService.createOrReuse(anyLong(), anyLong(), anyLong(), any(), eq("cancelOrder"), any()))
                .thenReturn(action);
        when(pendingActionService.summary(eq("cancelOrder"), any())).thenReturn("取消订单 O20261107001");
        when(pendingActionService.requireOwned(eq(7L), anyLong())).thenReturn(action);

        CapturingEmitter emitter = new CapturingEmitter();
        orchestrator.respond(emitter, session(), user("取消订单 O20261107001"));

        assertTrue(emitter.eventNames().contains("action"));
        assertTrue(emitter.actionJson().contains("cancelOrder"));
        assertTrue(emitter.actionJson().contains("O20261107001"));
    }

    private static ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setCustomerId(100L);
        session.setClientType("h5");
        session.setStatus(ChatSession.STATUS_AI_ONGOING);
        return session;
    }

    private static CsMessage user(String text) {
        CsMessage message = new CsMessage();
        message.setId(5L);
        message.setSessionId(1L);
        message.setRole(CsMessage.ROLE_USER);
        message.setContent(text);
        return message;
    }

    private static CsMessage message(Long id) {
        CsMessage message = new CsMessage();
        message.setId(id);
        message.setSessionId(1L);
        message.setRole(CsMessage.ROLE_ASSISTANT);
        message.setContent("ok");
        return message;
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

        String cardsJson() {
            return jsonFor("cards");
        }

        String actionJson() {
            return jsonFor("action");
        }

        String doneJson() {
            return jsonFor("done");
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
