package com.shangpivot.customerservice.agent.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.OrderBriefDTO;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.customerservice.agent.AnswerGuard;
import com.shangpivot.customerservice.agent.ChatResponder;
import com.shangpivot.customerservice.agent.FactAnswerRenderer;
import com.shangpivot.customerservice.agent.GuardPipeline;
import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.service.CartConfirmationPolicy;
import com.shangpivot.customerservice.agent.service.PendingActionService;
import com.shangpivot.customerservice.agent.service.PlatformWebhookService;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolRegistry;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.trace.AgentTraceService;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.llm.LlmClient;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import com.shangpivot.customerservice.retrieval.ProductConstraintParser;
import com.shangpivot.customerservice.retrieval.ProductConstraints;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import com.shangpivot.customerservice.service.ChatSessionService;
import com.shangpivot.customerservice.service.CsGuardRailService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3.1 图编排：route(Supervisor) → execute(Tool Gateway) → answer(Sub-Agent) → persist。
 * 仍然坚持 ADR-0005 的两阶段：模型不直接触发 native tool_calls，工具由后端执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGraphOrchestrator implements ChatResponder {

    private static final int MAX_HISTORY = 8;
    private static final int MAX_PRODUCTS = 3;

    private final LlmClient llmClient;
    private final LlmSettingsResolver settingsResolver;
    private final AgentPromptFactory promptFactory;
    private final SupervisorAgent supervisor;
    private final HandoffPolicy handoffPolicy;
    private final ToolRegistry toolRegistry;
    private final PendingActionService pendingActionService;
    private final CartConfirmationPolicy cartConfirmationPolicy;
    private final PlatformWebhookService platformWebhookService;
    private final AgentTraceService traceService;
    private final RunContextRegistry runContexts;
    private final ChatSessionService chatSessionService;
    private final CsGuardRailService guardRailService;
    private final CsMessageMapper messageMapper;
    private final ProductQueryApi productQueryApi;
    private final ProductSkuApi productSkuApi;
    private final RetrievalService retrievalService;
    private final GuardPipeline guardPipeline;
    private final ObjectMapper objectMapper;
    private final BaseCheckpointSaver checkpointSaver;
    private final AnswerGuard answerGuard;

    @Value("${agent.fact-answer-enabled:true}")
    private boolean factAnswerEnabled = true;
    @Value("${agent.retrieval-constraints-enabled:true}")
    private boolean retrievalConstraintsEnabled = true;

    private CompiledGraph<AgentGraphState> graph;

    @PostConstruct
    void init() {
        try {
            StateSerializer<AgentGraphState> serializer = new ObjectStreamStateSerializer<>(AgentGraphState::new);
            StateGraph<AgentGraphState> g = new StateGraph<>(serializer);
            g.addNode("route", AsyncNodeAction.node_async(this::routeNode));
            g.addNode("execute", AsyncNodeAction.node_async(this::executeNode));
            g.addNode("answer", AsyncNodeAction.node_async(this::answerNode));
            g.addEdge(StateGraph.START, "route");
            g.addConditionalEdges("route",
                    AsyncEdgeAction.edge_async(state -> "NEED_USER_INPUT".equals(state.status())
                            || "HANDOFF".equals(state.status()) ? "end" : "execute"),
                    Map.of("end", StateGraph.END, "execute", "execute"));
            g.addConditionalEdges("execute",
                    AsyncEdgeAction.edge_async(state -> "NEED_CONFIRMATION".equals(state.status()) ? "end" : "answer"),
                    Map.of("end", StateGraph.END, "answer", "answer"));
            g.addEdge("answer", StateGraph.END);
            this.graph = g.compile(CompileConfig.builder().checkpointSaver(checkpointSaver).build());
            log.info("[cs] agent graph compiled: route -> execute -> answer, checkpoint={}",
                    checkpointSaver.getClass().getSimpleName());
        } catch (Exception e) {
            throw new IllegalStateException("agent graph compile failed", e);
        }
    }

    @Override
    public void respond(SseEmitter emitter, ChatSession session, CsMessage userMsg) {
        String userText = userMsg.getContent() == null ? "" : userMsg.getContent();
        AgentRun run = traceService.startRun(session.getId(), session.getCustomerId(),
                session.getClientType(), null, null, null, null,
                Boolean.TRUE.equals(session.getIsTest()), session.getTestSource(), session.getTestRunId());
        Long runId = run.getId() == null || run.getId() == 0L ? System.nanoTime() : run.getId();
        runContexts.put(runId, new RunContextRegistry.RunContext(emitter, session, userMsg));
        long started = System.currentTimeMillis();
        AgentGraphState state = null;
        try {
            GuardPipeline.GuardResult guard = guardPipeline.inspectInput(userText);
            if (guard.injectionSuspected() && factAnswerEnabled) {
                rejectInjection(emitter, session, run, userMsg, started);
                return;
            }
            send(emitter, "state", Map.of("state", "ROUTING", "label", "正在理解您的问题…"));
            Map<String, Object> input = new HashMap<>();
            input.put("runId", runId);
            input.put("sessionId", session.getId());
            input.put("customerId", session.getCustomerId());
            input.put("userText", userText);
            input.put("channel", session.getClientType());
            // 每条消息独立 thread：避免 LangGraph4j 按 threadId 续跑上一轮 checkpoint；
            // runId 前缀便于按 agent_run 反查，UUID 兜底 trace 表未落库（runId=0）或测试复用 id 的情况。
            RunnableConfig config = RunnableConfig.builder()
                    .threadId("run-" + runId + "-" + UUID.randomUUID())
                    .build();
            state = graph.invoke(input, config).orElseThrow(() -> new IllegalStateException("empty graph result"));
            persistAndSend(emitter, session, userMsg, run, state, started);
        } catch (Exception e) {
            log.error("[cs] graph orchestration failed", e);
            degrade(emitter, session, run, state, e);
        } finally {
            runContexts.remove(runId);
            guardRailService.unlockSession(session.getId());
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
    }

    private void rejectInjection(SseEmitter emitter, ChatSession session, AgentRun run,
                                 CsMessage userMsg, long started) {
        String answer = FactAnswerRenderer.injectionRefusal();
        CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                answer, null, null, Map.of("guard", "injection"), false);
        run.setAgent("GUARD");
        run.setIntent("INJECTION");
        traceService.finishRun(run, "OK", 0, null, saved.getId());
        send(emitter, "delta", Map.of("delta", answer));
        send(emitter, "done", Map.of("messageId", saved.getId(), "sessionStatus", session.getStatus(),
                "degraded", false, "sources", List.of(), "agent", "GUARD", "intent", "INJECTION"));
    }

    // ---------- 图节点 ----------
    private Map<String, Object> routeNode(AgentGraphState state) {
        long start = System.currentTimeMillis();
        Long stepId = traceService.startStep(state.runId(), null, "route", null, "user=" + preview(state.userText()));
        Map<String, Object> out = new HashMap<>();
        if (handoffPolicy.shouldHandoff(state.userText())) {
            String sessionStatus = "QUEUED";
            try {
                ChatSession fresh = chatSessionService.escalate(state.sessionId(), state.customerId(), false);
                sessionStatus = fresh.getStatus();
            } catch (Exception e) {
                log.warn("[cs] auto handoff failed: {}", e.getMessage());
            }
            out.put("status", "HANDOFF");
            out.put("agent", "HANDOFF");
            out.put("intent", "HANDOFF");
            out.put("answer", "已为您转接人工客服，正在排队，请稍候~");
            out.put("displayAnswer", "已为您转接人工客服，正在排队，请稍候~");
            out.put("sessionStatus", sessionStatus);
            traceService.finishStep(stepId, "HANDOFF",
                    "handoff=" + handoffPolicy.reason(state.userText()), System.currentTimeMillis() - start, null);
            return out;
        }

        SupervisorAgent.RouteResult route = supervisor.route(state.userText());
        traceService.finishStep(stepId, "OK",
                "agent=" + route.agent().name() + ", action=" + route.action(), System.currentTimeMillis() - start, null);

        out.put("route", route);
        out.put("agent", route.agent().name());
        out.put("intent", route.intent().type().name());
        if (route.needOrderNo()) {
            out.put("status", "NEED_USER_INPUT");
            out.put("answer", "请提供要操作的订单号，我再帮您处理。");
            out.put("displayAnswer", "请提供要操作的订单号，我再帮您处理。");
        } else {
            out.put("status", "OK");
        }
        return out;
    }

    private Map<String, Object> executeNode(AgentGraphState state) {
        SupervisorAgent.RouteResult route = state.route();
        if (route == null) {
            return Map.of("status", "FAILED", "answer", "路由失败，请稍后重试。");
        }
        long start = System.currentTimeMillis();
        Long stepId = traceService.startStep(state.runId(), null, "execute", route.agent().name(),
                route.action().name());

        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        String facts;
        String displayAnswer = "";
        String status = "OK";
        String answer = null;
        Long pendingActionId = null;

        switch (route.action()) {
            case SEARCH_PRODUCTS -> {
                FactsResult result = searchProducts(state, route, cards, sources, tools);
                facts = result.facts();
                displayAnswer = result.displayAnswer();
            }
            case GET_STOCK -> {
                FactsResult result = stockFacts(state, route, cards, sources, tools);
                facts = result.facts();
                displayAnswer = result.displayAnswer();
            }
            case ADD_TO_CART -> {
                CartOutcome cart = addToCart(state, route, cards, sources, tools);
                facts = cart.facts();
                displayAnswer = cart.displayAnswer();
                status = cart.status();
                answer = cart.answer();
                pendingActionId = cart.pendingActionId();
            }
            case GET_ORDER -> {
                FactsResult result = orderFacts(state, route, sources, tools);
                facts = result.facts();
                displayAnswer = result.displayAnswer();
            }
            case SEARCH_KNOWLEDGE -> {
                FactsResult result = knowledgeFacts(state, route, sources, tools);
                facts = result.facts();
                displayAnswer = result.displayAnswer();
            }
            case CANCEL_ORDER, CONFIRM_RECEIVE, CREATE_AFTER_SALE -> {
                WriteOutcome write = prepareWrite(state, route, sources, tools);
                facts = write.facts();
                status = write.status();
                answer = write.answer();
                displayAnswer = write.answer();
                pendingActionId = write.pendingActionId();
            }
            default -> {
                facts = "";
                displayAnswer = chitchatDisplay(state.userText());
            }
        }

        traceService.finishStep(stepId, status, "tools=" + tools + ", facts=" + preview(facts),
                System.currentTimeMillis() - start, null);

        Map<String, Object> out = new HashMap<>();
        out.put("facts", facts == null ? "" : facts);
        out.put("displayAnswer", displayAnswer == null ? "" : displayAnswer);
        out.put("cards", cards);
        out.put("sources", sources);
        out.put("toolNames", tools);
        out.put("status", status);
        if (answer != null) {
            out.put("answer", answer);
        }
        if (pendingActionId != null) {
            out.put("pendingActionId", pendingActionId);
        }
        return out;
    }

    private record FactsResult(String facts, String displayAnswer) {
    }

    private record CartOutcome(String facts, String displayAnswer, String status, String answer,
                               Long pendingActionId) {
        static CartOutcome ok(String facts, String displayAnswer) {
            return new CartOutcome(facts, displayAnswer, "OK", null, null);
        }

        static CartOutcome confirm(String facts, String answer, Long pendingActionId) {
            return new CartOutcome(facts, answer, "NEED_CONFIRMATION", answer, pendingActionId);
        }
    }

    private String chitchatDisplay(String text) {
        String message = text == null ? "" : text;
        if (message.contains("你是谁") || message.contains("你叫什么") || message.contains("真人")
                || message.contains("身份")) {
            return FactAnswerRenderer.identity();
        }
        if (message.contains("天气") || message.contains("几点") || message.contains("新闻")) {
            return FactAnswerRenderer.weather();
        }
        return "";
    }

    private Map<String, Object> answerNode(AgentGraphState state) {
        if (!"OK".equals(state.status())) {
            return Map.of();
        }
        RunContextRegistry.RunContext ctx = runContexts.get(state.runId());
        if (ctx == null) {
            return Map.of("answer", state.answer(), "status", "OK");
        }
        // 高风险 route：后端事实直出，模型不参与商品/价格/政策生成
        if (factAnswerEnabled && !state.displayAnswer().isBlank()) {
            String answer = guardPipeline.filterOutput(state.displayAnswer());
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            return Map.of("answer", answer, "status", "OK");
        }
        String system = promptFactory.systemPrompt(state.route().agent()) + "\n\n" + state.facts();
        List<Map<String, String>> history = historyOf(state.sessionId());
        String fallback = fallbackText(state);

        if (!llmClient.available()) {
            String answer = answerGuard.guard(state.route().agent(), state.route().action(), state.facts(),
                    toCardDtos(state.cards()), state.displayAnswer(), fallback).answer();
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            return Map.of("answer", answer, "status", "OK");
        }

        send(ctx.emitter(), "state", Map.of("state", "ANSWERING", "label", "正在整理回答…"));
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        long start = System.currentTimeMillis();
        try {
            LlmClient.StreamResult result = llmClient.streamChat(system, history, new LlmClient.StreamListener() {
                @Override
                public void onReasoning(String delta) {
                    reasoning.append(delta);
                    send(ctx.emitter(), "reasoning", Map.of("delta", delta));
                }

                @Override
                public void onContent(String delta) {
                    // 高风险回答先缓冲，AnswerGuard 校验后再一次性下发
                    content.append(delta);
                }
            });
            String raw = result.content().isBlank() ? fallback : result.content();
            raw = guardPipeline.stripPlanningPrefix(raw);
            AnswerGuard.Result guarded = answerGuard.guard(state.route().agent(), state.route().action(),
                    state.facts(), toCardDtos(state.cards()), state.displayAnswer(), raw);
            String answer = guardPipeline.filterOutput(guarded.answer());
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            long latency = System.currentTimeMillis() - start;
            long tokens = result.promptTokens() + result.completionTokens();
            traceService.recordLlmCall(state.runId(), null, settingsModel(), promptFactory.promptVersion(state.route().agent()),
                    (int) result.promptTokens(), (int) result.completionTokens(), latency,
                    guarded.replaced() ? "answer_guard" : "stop",
                    guarded.replaced() ? "FALLBACK" : "SUCCESS", preview(system), preview(answer));
            return Map.of("answer", answer, "reasoning", reasoning.toString(), "status", "OK",
                    "totalTokens", tokens, "promptTokens", result.promptTokens(),
                    "completionTokens", result.completionTokens());
        } catch (Exception e) {
            log.warn("[cs] graph answer failed, fallback: {}", e.getMessage());
            String answer = guardPipeline.filterOutput(state.displayAnswer().isBlank()
                    ? FactAnswerRenderer.chitchatFallback() : state.displayAnswer());
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            traceService.recordLlmCall(state.runId(), null, settingsModel(), promptFactory.promptVersion(state.route().agent()),
                    0, 0, System.currentTimeMillis() - start, "error", "FAILED", preview(system), preview(answer));
            return Map.of("answer", answer, "status", "OK");
        }
    }

    private List<ProductCardDTO> toCardDtos(List<Map<String, Object>> cards) {
        List<ProductCardDTO> result = new ArrayList<>();
        if (cards == null) {
            return result;
        }
        for (Map<String, Object> card : cards) {
            result.add(new ProductCardDTO(
                    card.get("spuId") instanceof Number n ? n.longValue() : null,
                    String.valueOf(card.get("name")),
                    card.get("subtitle") == null ? null : String.valueOf(card.get("subtitle")),
                    card.get("pic") == null ? null : String.valueOf(card.get("pic")),
                    decimal(card.get("price")),
                    decimal(card.get("priceMax")),
                    card.get("soldCount") instanceof Number n ? n.longValue() : null));
        }
        return result;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 事实收集（通过 Tool Gateway） ----------

    private FactsResult searchProducts(AgentGraphState state, SupervisorAgent.RouteResult route,
                                       List<Map<String, Object>> cards, List<String> sources, List<String> tools) {
        String keyword = route.intent().keyword();
        if (keyword == null || keyword.isBlank()) {
            keyword = IntentRouter.extractKeyword(state.userText());
        }
        ProductConstraints constraints = constraints(state.userText());
        if (discountOnlyQuery(state.userText(), constraints)) {
            traceService.recordRetrieval(state.runId(), keyword, MAX_PRODUCTS,
                    List.of(), List.of(), 0, false, "product");
            return new FactsResult("（优惠咨询：无专属优惠，如实告知；不要推荐无关商品）",
                    FactAnswerRenderer.noProducts(keyword, state.userText()));
        }
        boolean compareQuery = state.userText()
                .matches(".*(和|与|对比|哪个好|哪个更|哪个便宜|哪个贵|哪个强|vs|VS).*");
        ToolResult result = tool(state, route, "searchProducts",
                searchArgs(keyword, compareQuery ? 1 : MAX_PRODUCTS, constraints), tools);
        List<RetrievalService.ProductHit> hits = new ArrayList<>(dataList(result, RetrievalService.ProductHit.class));
        // 对比/多品类查询：按“和/与/对比/哪个好”拆段各检索一次，避免只命中其中一款
        if (compareQuery) {
            Map<Long, RetrievalService.ProductHit> merged = new LinkedHashMap<>();
            hits.forEach(hit -> merged.put(hit.card().spuId(), hit));
            for (String part : state.userText().split("(?:和|与|对比|哪个好|哪个更|哪个便宜|哪个贵|哪个强|vs|VS)")) {
                String partKeyword = IntentRouter.extractKeyword(part);
                if (partKeyword.isBlank() || partKeyword.equals(keyword)) {
                    continue;
                }
                ToolResult extra = tool(state, route, "searchProducts",
                        searchArgs(partKeyword, 2, constraints(part)), tools);
                dataList(extra, RetrievalService.ProductHit.class)
                        .forEach(hit -> merged.putIfAbsent(hit.card().spuId(), hit));
            }
            hits = new ArrayList<>(merged.values());
            if (hits.size() > MAX_PRODUCTS) {
                hits = new ArrayList<>(hits.subList(0, MAX_PRODUCTS));
            }
        }
        List<ProductCardDTO> previous = lastRecommendedCards(state.sessionId());
        if (!hits.isEmpty()) {
            List<ProductCardDTO> hitCards = new ArrayList<>();
            for (RetrievalService.ProductHit hit : hits) {
                ProductCardDTO card = hit.card();
                hitCards.add(card);
                cards.add(cardMap(card));
                sources.add(hit.source() + ":" + card.spuId());
            }
            traceService.recordRetrieval(state.runId(), keyword, MAX_PRODUCTS,
                    hits.stream().map(hit -> (Object) hit.card().spuId()).toList(),
                    hits.stream().map(hit -> (Object) hit.score()).toList(),
                    0, true, "product");
            String hint = hits.stream().anyMatch(hit -> !hit.withinBudget())
                    ? "带“超预算”标注的商品不符合预算，仅作对比" : "";
            String display = FactAnswerRenderer.presale(keyword, hits, previous,
                    constraints.budgetLabel(), hint, constraints.explicitNewQuery(), state.userText());
            return new FactsResult(productFacts(hitCards), display);
        }
        traceService.recordRetrieval(state.runId(), keyword, MAX_PRODUCTS,
                List.of(), List.of(), 0, false, "product");
        if (!constraints.explicitNewQuery() && !previous.isEmpty()) {
            for (ProductCardDTO card : previous) {
                cards.add(cardMap(card));
                sources.add("context:" + card.spuId());
            }
            return new FactsResult("（本轮未检索到新商品；以下为此前推荐的真实商品，围绕它们回答，禁止编造其它商品）\n"
                    + productFacts(previous), FactAnswerRenderer.previousProducts(previous));
        }
        return new FactsResult("（商品检索无结果：如实告知未找到匹配商品，可建议换个说法或去分类页逛逛）",
                FactAnswerRenderer.noProducts(keyword, state.userText()));
    }

    private ProductConstraints constraints(String text) {
        return retrievalConstraintsEnabled ? ProductConstraintParser.parse(text) : ProductConstraints.none();
    }

    /** 纯优惠/PII 咨询：用户没有点名商品时只回答优惠，不拿品类检索凑商品。 */
    private boolean discountOnlyQuery(String text, ProductConstraints constraints) {
        if (text == null || constraints.hasCategories()) {
            return false;
        }
        boolean discount = text.contains("优惠") || text.contains("折扣")
                || text.contains("活动价") || text.contains("便宜点");
        if (!discount) {
            return false;
        }
        return !text.matches(".*(Redmi|iPhone|Xiaomi|HUAWEI|OPPO|vivo|荣耀|索尼|AirPods|戴森|石头|MacBook|ThinkPad|ROG|拯救者|Dell|美的|海尔|Anker|Apple Watch|iQOO|一加|红米|华为|小米).*");
    }

    private Map<String, Object> searchArgs(String keyword, int limit, ProductConstraints constraints) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("limit", limit);
        if (constraints.hasCategories()) {
            args.put("categories", constraints.categories());
        }
        if (constraints.budgetMin() != null) {
            args.put("priceMin", constraints.budgetMin());
        }
        if (constraints.budgetMax() != null) {
            args.put("priceMax", constraints.budgetMax());
        }
        return args;
    }

    private FactsResult stockFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
                                   List<Map<String, Object>> cards, List<String> sources, List<String> tools) {
        ProductCardDTO product = resolveProduct(state.sessionId(), state.userText(), route.productRef());
        if (product == null) {
            return askWhichProduct(state, cards, sources);
        }
        cards.add(cardMap(product));
        sources.add("sku:" + product.spuId());
        ToolResult result = tool(state, route, "getStock", Map.of("spuId", product.spuId()), tools);
        List<ProductSkuDTO> skus = dataList(result, ProductSkuDTO.class);
        if (skus.isEmpty()) {
            return new FactsResult("（该商品暂无可售规格：如实告知，建议关注补货或转人工）",
                    "《" + product.name() + "》暂无可售规格，建议关注补货或转人工。");
        }
        boolean stockQuestion = state.userText().contains("有货")
                && !state.userText().matches(".*(规格|参数|配置|颜色|版本|型号).*");
        return new FactsResult(skuFacts(product, skus),
                stockQuestion ? FactAnswerRenderer.availability(product, skus)
                        : FactAnswerRenderer.specs(product, skus));
    }

    private CartOutcome addToCart(AgentGraphState state, SupervisorAgent.RouteResult route,
                                  List<Map<String, Object>> cards, List<String> sources, List<String> tools) {
        ProductCardDTO product = resolveProduct(state.sessionId(), state.userText(), route.productRef());
        if (product == null) {
            FactsResult ask = askWhichProduct(state, cards, sources);
            return CartOutcome.ok(ask.facts(), ask.displayAnswer());
        }
        cards.add(cardMap(product));
        ToolResult stock = tool(state, route, "getStock", Map.of("spuId", product.spuId()), tools);
        List<ProductSkuDTO> skus = dataList(stock, ProductSkuDTO.class);
        if (skus.isEmpty()) {
            return CartOutcome.ok("（该商品暂无可售规格，无法加购：如实告知）",
                    "《" + product.name() + "》暂无可售规格，暂时无法加购。");
        }
        ProductSkuDTO chosen = pickSku(skus, state.userText());
        if (chosen == null) {
            sources.add("sku:" + product.spuId());
            return CartOutcome.ok("（该商品有多个规格，用户未说明要哪个：请列出以下规格让用户选择，不要自行加购）\n"
                    + skuFacts(product, skus), FactAnswerRenderer.addCartClarify(product, skus));
        }
        int qty = Math.max(1, Math.min(route.quantity(), Math.max(chosen.availableStock(), 1)));

        // 风险分级：单规格、1 件、金额低于阈值直接加购；否则生成待确认动作。
        if (cartConfirmationPolicy.requiresConfirmation(product, chosen, qty)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("skuId", chosen.skuId());
            args.put("quantity", qty);
            args.put("productName", product.name());
            args.put("skuTitle", chosen.title());
            args.put("price", chosen.price());
            PendingAction action = pendingActionService.createOrReuse(state.sessionId(), state.customerId(),
                    state.runId(), route.agent(), "addToCart", args);
            String summary = pendingActionService.summary("addToCart", args);
            sources.add("cart-confirm:" + chosen.skuId());
            return CartOutcome.confirm("（已生成待确认加购动作：" + summary + "；不要声称已加购）",
                    FactAnswerRenderer.pendingWrite(summary), action.getId());
        }

        ToolResult result = tool(state, route, "addToCart",
                Map.of("skuId", chosen.skuId(), "quantity", qty), tools);
        if (!result.success()) {
            return CartOutcome.ok("（加购失败：" + result.errorMessage() + "。如实告知用户失败原因）",
                    FactAnswerRenderer.addCartFail(result.errorMessage()));
        }
        sources.add("cart:" + chosen.skuId());
        return CartOutcome.ok("（已成功为用户加入购物车：商品「" + product.name() + "」，规格「" + chosen.title()
                + "」，单价 " + chosen.price() + " 元，本次加入 " + qty + " 件。告知用户已加购，可到购物车结算）",
                FactAnswerRenderer.addCartSuccess(product, chosen, qty, null));
    }

    private FactsResult orderFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
                                   List<String> sources, List<String> tools) {
        String orderNo = route.orderNo();
        Map<String, Object> args = orderNo == null || orderNo.isBlank()
                ? Map.of() : Map.of("orderNo", orderNo);
        ToolResult result = tool(state, route, "getOrder", args, tools);
        if (!result.success()) {
            if (orderNo != null && !orderNo.isBlank()) {
                sources.add("order:" + orderNo);
                return new FactsResult("（" + result.errorMessage() + "：如实告知，并提醒核对订单号）",
                        FactAnswerRenderer.orderNotFound(orderNo));
            }
            return new FactsResult("（" + result.errorMessage() + "：如实告知）", FactAnswerRenderer.noOrder());
        }
        if (result.data() instanceof OrderTraceDTO trace) {
            sources.add("order:" + trace.orderNo());
            StringBuilder sb = new StringBuilder("当前会员的订单数据（如实引用）：\n");
            sb.append("订单 ").append(trace.orderNo()).append("，状态 ").append(statusText(trace.status()))
                    .append("，实付 ").append(trace.payAmount() == null ? "-" : trace.payAmount()).append(" 元");
            if (trace.carrier() != null) {
                sb.append("；物流：").append(trace.carrier()).append(" ")
                        .append(trace.trackingNo() == null ? "" : trace.trackingNo())
                        .append("（").append(trace.shipmentStatus() == null ? "-" : trace.shipmentStatus()).append("）");
                if (trace.traces() != null && !trace.traces().isEmpty()) {
                    sb.append("；最近轨迹：").append(trailText(trace.traces()));
                }
            } else {
                sb.append("；暂无发货记录");
            }
            return new FactsResult(sb.append("\n").toString(), FactAnswerRenderer.orderTrace(trace));
        }
        List<OrderBriefDTO> orders = dataList(result, OrderBriefDTO.class);
        sources.add("order:list");
        if (orders.isEmpty()) {
            return new FactsResult("（该会员名下没有订单：如实告知）", FactAnswerRenderer.noOrder());
        }
        StringBuilder sb = new StringBuilder("当前会员的订单数据（如实引用）：\n");
        for (OrderBriefDTO order : orders) {
            sb.append("订单 ").append(order.orderNo()).append("，状态 ").append(statusText(order.status()))
                    .append("，实付 ").append(order.payAmount() == null ? "-" : order.payAmount()).append(" 元");
            if (order.shipAt() != null) {
                sb.append("，已发货");
            }
            sb.append("\n");
        }
        return new FactsResult(sb.toString(), FactAnswerRenderer.orders(orders));
    }

    private FactsResult knowledgeFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
                                       List<String> sources, List<String> tools) {
        String query = route.agent() == AgentName.AFTER_SALE
                ? "退货 换货 售后 " + state.userText() : state.userText();
        ToolResult result = tool(state, route, "searchKnowledge", Map.of("query", query, "topK", 2), tools);
        List<KnowledgeSearchService.KnowledgeHit> hits = dataList(result, KnowledgeSearchService.KnowledgeHit.class);
        traceService.recordRetrieval(state.runId(), query, 2,
                hits.stream().map(hit -> (Object) hit.docId()).toList(),
                hits.stream().map(hit -> (Object) hit.score()).toList(),
                0, !hits.isEmpty(), "knowledge");
        StringBuilder sb = new StringBuilder("知识资料（如实引用，禁止编造；引用《标题》）：\n");
        for (KnowledgeSearchService.KnowledgeHit hit : hits) {
            sb.append("《").append(hit.title()).append("》：").append(hit.snippet()).append("\n");
            sources.add("doc:" + hit.docId());
        }
        if (hits.isEmpty()) {
            sb.append("（无匹配知识：如实告知，建议换问法或转人工）\n");
        }
        return new FactsResult(sb.toString(), FactAnswerRenderer.knowledge(hits,
                route.agent() == AgentName.AFTER_SALE ? state.userText() : null));
    }

    private WriteOutcome prepareWrite(AgentGraphState state, SupervisorAgent.RouteResult route,
                                      List<String> sources, List<String> tools) {
        String orderNo = route.orderNo();
        ToolResult check = tool(state, route, "getOrder", Map.of("orderNo", orderNo), tools);
        if (!check.success()) {
            if (orderNo != null && !orderNo.isBlank()) {
                sources.add("order:" + orderNo);
            }
            return new WriteOutcome("（" + check.errorMessage() + "：如实告知，并提醒核对订单号）",
                    "OK", FactAnswerRenderer.orderNotFound(orderNo), null);
        }
        if (check.data() instanceof OrderTraceDTO trace) {
            sources.add("order:" + trace.orderNo());
        }
        String toolName = switch (route.action()) {
            case CANCEL_ORDER -> "cancelOrder";
            case CONFIRM_RECEIVE -> "confirmReceive";
            case CREATE_AFTER_SALE -> "createAfterSale";
            default -> null;
        };
        if (toolName == null) {
            return new WriteOutcome("（不支持的操作）", "FAILED", "抱歉，这个操作暂时不支持。", null);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderNo", orderNo);
        if ("createAfterSale".equals(toolName)) {
            args.put("type", afterSaleType(state.userText()));
            args.put("reason", state.userText().length() > 500
                    ? state.userText().substring(0, 500) : state.userText());
        }
        PendingAction action = pendingActionService.createOrReuse(state.sessionId(), state.customerId(),
                state.runId(), route.agent(), toolName, args);
        String summary = pendingActionService.summary(toolName, args);
        return new WriteOutcome("（已生成待确认动作：" + summary + "；不要声称已执行）",
                "NEED_CONFIRMATION", FactAnswerRenderer.pendingWrite(summary), action.getId());
    }

    private record WriteOutcome(String facts, String status, String answer, Long pendingActionId) {
    }

    // ---------- 持久化与 SSE ----------

    private void persistAndSend(SseEmitter emitter, ChatSession session, CsMessage userMsg,
                                AgentRun run, AgentGraphState state, long started) {
        String answer = state.answer();
        if (answer == null || answer.isBlank()) {
            answer = fallbackText(state);
        }
        Map<String, Object> metadata = state.cards().isEmpty() ? null : Map.of(CsMessage.META_ITEMS, state.cards());
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("runId", state.runId());
        trace.put("agent", state.route() == null ? null : state.route().agent().name());
        trace.put("tools", state.toolNames());
        trace.put("sources", state.sources());
        CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                answer, state.reasoning(), metadata, trace, false);

        // 真实数据 webhook：业务事务只写 outbox，异步投递；测试流量默认不推送。
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("externalSessionId", String.valueOf(session.getId()));
        event.put("externalRunId", String.valueOf(state.runId()));
        event.put("externalMessageId", String.valueOf(saved.getId()));
        event.put("isTest", Boolean.TRUE.equals(session.getIsTest()));
        event.put("testSource", session.getTestSource());
        event.put("clientType", session.getClientType());
        event.put("userText", state.userText());
        event.put("answer", answer);
        event.put("cards", state.cards());
        event.put("sources", state.sources());
        event.put("tools", state.toolNames());
        event.put("intent", state.route() == null ? null : state.route().intent().type().name());
        event.put("agent", state.route() == null ? null : state.route().agent().name());
        event.put("status", state.status());
        event.put("latencyMs", System.currentTimeMillis() - started);
        event.put("tokens", state.totalTokens());
        // AI 自动测试不推送（平台自己记录）；人工体验要推送到真实数据看板。
        boolean suppress = Boolean.TRUE.equals(session.getIsTest())
                && !"human".equals(session.getTestSource());
        platformWebhookService.enqueue("run.finished", event, suppress);

        if (!state.cards().isEmpty()) {
            send(emitter, "cards", Map.of(CsMessage.META_ITEMS, state.cards()));
        }
        if ("NEED_CONFIRMATION".equals(state.status()) && state.pendingActionId() != null) {
            PendingAction action = pendingActionService.requireOwned(state.pendingActionId(), session.getCustomerId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", action.getId());
            payload.put("tool", action.getToolName());
            payload.put("summary", pendingActionService.summary(action.getToolName(), action.getArgsJson()));
            payload.put("args", action.getArgsJson());
            payload.put("expiresAt", action.getExpiresAt());
            send(emitter, "action", payload);
            send(emitter, "delta", Map.of("delta", answer));
        } else if ("NEED_USER_INPUT".equals(state.status()) || "HANDOFF".equals(state.status())
                || "FAILED".equals(state.status())) {
            send(emitter, "delta", Map.of("delta", answer));
        }

        String status = "FAILED".equals(state.status()) ? "FAILED" : state.status();
        if (state.route() != null) {
            run.setAgent(state.route().agent().name());
            run.setIntent(state.route().intent().type().name());
            run.setModel(settingsModel());
            run.setPromptVersion(promptFactory.promptVersion(state.route().agent()));
        } else if ("HANDOFF".equals(state.status())) {
            run.setAgent("HANDOFF");
            run.setIntent("HANDOFF");
        }
        traceService.finishRun(run, status, state.totalTokens(), null, saved.getId());
        chatSessionService.autoSummarize(session.getId());

        Map<String, Object> done = new HashMap<>();
        done.put("messageId", saved.getId());
        done.put("sessionStatus", state.sessionStatus().isBlank() ? session.getStatus() : state.sessionStatus());
        done.put("degraded", false);
        done.put("runId", state.runId());
        done.put("sources", state.sources());
        if (state.route() != null) {
            done.put("agent", state.route().agent().name());
            done.put("intent", state.route().intent().type().name());
        } else if ("HANDOFF".equals(state.status())) {
            done.put("agent", "HANDOFF");
            done.put("intent", "HANDOFF");
        }
        done.put("tools", state.toolNames());
        if (state.promptTokens() > 0 || state.completionTokens() > 0) {
            done.put("tokens", Map.of("prompt", state.promptTokens(), "completion", state.completionTokens()));
        } else if (state.totalTokens() > 0) {
            done.put("tokens", Map.of("total", state.totalTokens()));
        }
        send(emitter, "done", done);
    }

    private void degrade(SseEmitter emitter, ChatSession session, AgentRun run,
                         AgentGraphState state, Exception error) {
        String text = state == null ? "智能助手暂时繁忙，请稍后再试或转人工。"
                : fallbackText(state);
        try {
            CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                    text, null, null, null, true);
            send(emitter, "delta", Map.of("delta", text));
            send(emitter, "done", Map.of("messageId", saved.getId(), "sessionStatus", session.getStatus(),
                    "degraded", true, "sources", List.of()));
            traceService.finishRun(run, "FAILED", 0, error.getMessage(), saved.getId());
        } catch (Exception e) {
            log.warn("[cs] degrade failed: {}", e.getMessage());
            send(emitter, "error", Map.of("code", 50000, "msg", "智能客服开小差了，请重试"));
            send(emitter, "done", Map.of("messageId", 0, "sessionStatus", session.getStatus(), "degraded", true));
        }
    }

    // ---------- 商品上下文 ----------

    private ProductCardDTO resolveProduct(Long sessionId, String userText, String productRef) {
        List<ProductCardDTO> previous = lastRecommendedCards(sessionId);
        String ref = productRef == null ? "" : productRef.trim();
        String text = userText == null ? "" : userText;

        Matcher idx = Pattern.compile("第\\s*([一二三四五1-5])").matcher(ref + " " + text);
        if (idx.find()) {
            int n = "一二三四五".indexOf(idx.group(1));
            int zero = n >= 0 ? n : (Integer.parseInt(idx.group(1)) - 1);
            if (zero >= 0 && zero < previous.size()) {
                return previous.get(zero);
            }
        }
        String textNorm = IntentRouter.normalizeProductText(text);
        String refNorm = IntentRouter.normalizeProductText(ref);
        for (ProductCardDTO card : previous) {
            String name = card.name();
            String head = name.length() > 4 ? name.substring(0, 4) : name;
            String nameNorm = IntentRouter.normalizeProductText(name);
            String headNorm = IntentRouter.normalizeProductText(head);
            if ((!headNorm.isBlank() && textNorm.contains(headNorm))
                    || (refNorm.length() >= 2 && nameNorm.contains(refNorm))) {
                return card;
            }
        }
        if (ref.matches("\\d+")) {
            // LLM 可能从历史里带出旧 spuId；用户本轮明确点名商品时以文本为准
            boolean namesProduct = text.matches(".*(Redmi|iPhone|Xiaomi|HUAWEI|OPPO|vivo|荣耀|索尼|AirPods|戴森|石头|MacBook|ThinkPad|ROG|拯救者|Dell|美的|海尔|Anker|Apple Watch|iQOO|一加|红米|华为|小米).*");
            if (!namesProduct) {
                List<ProductCardDTO> named = productQueryApi.getBySpuIds(List.of(Long.parseLong(ref)));
                if (!named.isEmpty()) {
                    return named.get(0);
                }
            }
        }
        // 用户点名了目录里的其它商品（如“那 iPhone 呢”）：优先于“上文唯一”的代词兜底
        String keyword = IntentRouter.extractKeyword(text)
                .replace("那", "")
                .replace("有货", "")
                .replace("库存", "")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*有$", "")
                .trim();
        List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(keyword, 5, constraints(text));
        String textLower = text.toLowerCase(java.util.Locale.ROOT);
        // 先按“关键词/显式商品名”精确命中；多个命中（如泛称 iPhone）取价格最低的基础款，
        // 避免 iPhone 15 Pro 抢先于 iPhone 15。
        String keywordNorm = IntentRouter.normalizeProductText(keyword);
        List<ProductCardDTO> exact = new ArrayList<>();
        for (RetrievalService.ProductHit hit : hits) {
            String nameNorm = IntentRouter.normalizeProductText(hit.card().name());
            boolean keywordHit = keywordNorm.length() >= 2
                    && (nameNorm.contains(keywordNorm) || keywordNorm.contains(nameNorm));
            boolean refHit = refNorm.length() >= 2 && nameNorm.contains(refNorm);
            if (keywordHit || refHit) {
                exact.add(hit.card());
            }
        }
        if (!exact.isEmpty()) {
            return exact.stream()
                    .min(java.util.Comparator.comparing(card ->
                            card.price() == null ? new java.math.BigDecimal("999999999") : card.price()))
                    .orElse(exact.get(0));
        }
        for (RetrievalService.ProductHit hit : hits) {
            String name = hit.card().name();
            String head = name.length() > 4 ? name.substring(0, 4) : name;
            if (textLower.contains(head.toLowerCase(java.util.Locale.ROOT))) {
                return hit.card();
            }
        }
        if (!previous.isEmpty() && (ref.isBlank() || ref.matches(".*(它|这个|那个|刚才|上面|这款|同款).*"))) {
            return previous.size() == 1 ? previous.get(0) : null;
        }
        return hits.size() == 1 ? hits.get(0).card() : null;
    }

    private FactsResult askWhichProduct(AgentGraphState state, List<Map<String, Object>> cards, List<String> sources) {
        List<ProductCardDTO> previous = lastRecommendedCards(state.sessionId());
        if (previous.isEmpty()) {
            return new FactsResult("（用户想操作商品但当前会话没有可指代的商品：请用户说明具体是哪款，或先为其推荐）",
                    FactAnswerRenderer.noProductContext());
        }
        StringBuilder sb = new StringBuilder("（用户指代不明：请列出以下此前推荐过的商品，请用户确认后再继续，禁止瞎猜）\n");
        sources.add("context-ask");
        for (ProductCardDTO card : previous) {
            cards.add(cardMap(card));
            sb.append("【商品】").append(card.name()).append("（").append(card.price()).append(" 元）\n");
        }
        return new FactsResult(sb.toString(), FactAnswerRenderer.clarifyProducts(previous));
    }

    private ProductSkuDTO pickSku(List<ProductSkuDTO> skus, String userText) {
        String text = userText == null ? "" : userText;
        // 规格序号：“第一个/第2款/规格1/选1”
        // 只识别“规格 1/规格第一个/选 1”或单独数字，避免把“购买第一款”的商品序号当成规格序号。
        java.util.regex.Matcher ordinal = java.util.regex.Pattern
                .compile("(?:规格\\s*第?\\s*|选\\s*)([一二三四五1-5])\\s*(?:个|款|号|规格)?")
                .matcher(text);
        boolean ordinalFound = ordinal.find();
        if (!ordinalFound) {
            ordinal = java.util.regex.Pattern.compile("^\\s*([1-5])\\s*$").matcher(text);
            ordinalFound = ordinal.find();
        }
        if (ordinalFound) {
            int n = "一二三四五".indexOf(ordinal.group(1));
            int index = n >= 0 ? n : Integer.parseInt(ordinal.group(1)) - 1;
            if (index >= 0 && index < skus.size()) {
                return skus.get(index);
            }
        }
        for (ProductSkuDTO sku : skus) {
            if (text.contains(sku.title())) {
                return sku;
            }
            if (sku.specs() != null) {
                for (Object value : sku.specs().values()) {
                    String s = String.valueOf(value);
                    if (s.length() >= 2 && text.contains(s)) {
                        return sku;
                    }
                }
            }
        }
        return skus.size() == 1 ? skus.get(0) : null;
    }

    private List<ProductCardDTO> lastRecommendedCards(Long sessionId) {
        try {
            CsMessage last = messageMapper.selectOne(new LambdaQueryWrapper<CsMessage>()
                    .eq(CsMessage::getSessionId, sessionId)
                    .eq(CsMessage::getRole, CsMessage.ROLE_ASSISTANT)
                    .eq(CsMessage::getContentType, CsMessage.TYPE_PRODUCT_CARDS)
                    .orderByDesc(CsMessage::getId)
                    .last("LIMIT 1"));
            if (last == null || last.getMetadata() == null || last.getMetadata().get(CsMessage.META_ITEMS) == null) {
                return List.of();
            }
            List<Map<String, Object>> items = objectMapper.convertValue(last.getMetadata().get(CsMessage.META_ITEMS),
                    new TypeReference<List<Map<String, Object>>>() { });
            List<Long> spuIds = items.stream()
                    .map(m -> m.get("spuId"))
                    .filter(java.util.Objects::nonNull)
                    .map(v -> Long.valueOf(String.valueOf(v)))
                    .toList();
            if (spuIds.isEmpty()) {
                return List.of();
            }
            Map<Long, ProductCardDTO> byId = productQueryApi.getBySpuIds(spuIds).stream()
                    .collect(java.util.stream.Collectors.toMap(ProductCardDTO::spuId, c -> c, (a, b) -> a));
            return spuIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            log.debug("[cs] read previous cards failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------- 工具 ----------

    private ToolResult tool(AgentGraphState state, SupervisorAgent.RouteResult route,
                            String name, Map<String, Object> args, List<String> tools) {
        tools.add(name);
        ToolContext context = new ToolContext(state.customerId(), state.sessionId(), state.runId(),
                null, route.agent(), false,
                "addToCart".equals(name) ? "run:" + state.runId() + ":" + args : null);
        return toolRegistry.execute(context, name, args);
    }

    private <T> List<T> dataList(ToolResult result, Class<T> type) {
        if (result == null || !result.success() || !(result.data() instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ---------- 文案 ----------

    private String productFacts(List<ProductCardDTO> cards) {
        StringBuilder sb = new StringBuilder("以下是可推荐的真实在售商品（只能推荐这些，禁止编造）：\n");
        int i = 1;
        for (ProductCardDTO card : cards) {
            sb.append("【商品").append(i++).append("】").append(card.name());
            if (card.subtitle() != null && !card.subtitle().isBlank()) {
                sb.append("（").append(card.subtitle()).append("）");
            }
            sb.append("，价格 ").append(card.price() == null ? "询价" : card.price());
            if (card.priceMax() != null) {
                sb.append("~").append(card.priceMax());
            }
            sb.append("，已售 ").append(card.soldCount() == null ? 0 : card.soldCount()).append("\n");
        }
        return sb.toString();
    }

    private String skuFacts(ProductCardDTO product, List<ProductSkuDTO> skus) {
        StringBuilder sb = new StringBuilder("用户询问的商品与其真实规格（如实引用，禁止编造）：\n");
        sb.append("商品：").append(product.name()).append("\n可选规格：\n");
        int i = 1;
        for (ProductSkuDTO sku : skus) {
            sb.append("【规格").append(i++).append("】").append(sku.title());
            if (sku.specs() != null && !sku.specs().isEmpty()) {
                sb.append("，参数：").append(sku.specs());
            }
            sb.append("，价格 ").append(sku.price()).append(" 元，库存 ")
                    .append(sku.availableStock() <= 0 ? "紧张/无货" : sku.availableStock()).append("\n");
        }
        return sb.toString();
    }

    private static String orderNotFound(String facts) {
        String text = facts == null ? "" : facts.trim();
        if (text.startsWith("（")) {
            text = text.substring(1);
        }
        if (text.endsWith("）")) {
            text = text.substring(0, text.length() - 1);
        }
        int cut = text.indexOf("：");
        if (cut > 0) {
            text = text.substring(0, cut) + "，请核对订单号。";
        }
        return text;
    }

    private static String cartConfirmation(String facts) {
        String text = facts == null ? "" : facts.trim();
        if (text.startsWith("（")) {
            text = text.substring(1);
        }
        if (text.endsWith("）")) {
            text = text.substring(0, text.length() - 1);
        }
        int cut = text.indexOf("。告知用户");
        if (cut > 0) {
            text = text.substring(0, cut) + "。可到购物车结算～";
        }
        return text;
    }

    private String fallbackText(AgentGraphState state) {
        String display = state.displayAnswer();
        if (display != null && !display.isBlank()) {
            return display;
        }
        return FactAnswerRenderer.chitchatFallback();
    }

    private List<Map<String, String>> historyOf(Long sessionId) {
        List<CsMessage> latest = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, sessionId)
                .orderByDesc(CsMessage::getId)
                .last("LIMIT " + MAX_HISTORY));
        List<Map<String, String>> history = new ArrayList<>();
        for (CsMessage message : latest.reversed()) {
            String role = CsMessage.ROLE_USER.equals(message.getRole()) ? "user" : "assistant";
            String content = message.getContent() == null ? "" : message.getContent();
            if (!content.isBlank()) {
                history.add(Map.of("role", role, "content", content));
            }
        }
        return history;
    }

    private String settingsModel() {
        return settingsResolver.resolve().getModel();
    }

    private Map<String, Object> cardMap(ProductCardDTO card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spuId", card.spuId());
        map.put("name", card.name());
        map.put("subtitle", card.subtitle());
        map.put("pic", card.pic());
        map.put("price", card.price());
        map.put("priceMax", card.priceMax());
        map.put("soldCount", card.soldCount());
        return map;
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING_PAY" -> "待付款";
            case "PENDING_SHIP" -> "待发货";
            case "PENDING_RECEIVE" -> "待收货";
            case "COMPLETED" -> "已完成";
            case "CLOSED" -> "已关闭";
            default -> status == null ? "-" : status;
        };
    }

    private static String trailText(List<Map<String, Object>> traces) {
        Map<String, Object> last = traces.get(traces.size() - 1);
        Object desc = last.get("desc") != null ? last.get("desc") : last.get("content");
        return desc == null ? "" : desc.toString();
    }

    private static String afterSaleType(String text) {
        if (text.contains("换货")) {
            return "EXCHANGE";
        }
        if (text.contains("退货")) {
            return "RETURN_REFUND";
        }
        return "REFUND";
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.substring(0, Math.min(120, text.length()));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("[cs] SSE send failed: {}", e.getMessage());
        }
    }
}
