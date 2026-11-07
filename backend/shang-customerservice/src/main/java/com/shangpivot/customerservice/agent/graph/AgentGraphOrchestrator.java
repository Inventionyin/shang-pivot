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
import com.shangpivot.customerservice.agent.ChatResponder;
import com.shangpivot.customerservice.agent.GuardPipeline;
import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.service.PendingActionService;
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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
                session.getClientType(), null, null, null, null);
        Long runId = run.getId() == null || run.getId() == 0L ? System.nanoTime() : run.getId();
        runContexts.put(runId, new RunContextRegistry.RunContext(emitter, session, userMsg));
        long started = System.currentTimeMillis();
        AgentGraphState state = null;
        try {
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
        String status = "OK";
        String answer = null;
        Long pendingActionId = null;

        switch (route.action()) {
            case SEARCH_PRODUCTS -> facts = searchProducts(state, route, cards, sources, tools);
            case GET_STOCK -> facts = stockFacts(state, route, cards, sources, tools);
            case ADD_TO_CART -> facts = addToCart(state, route, cards, sources, tools);
            case GET_ORDER -> facts = orderFacts(state, route, sources, tools);
            case SEARCH_KNOWLEDGE -> facts = knowledgeFacts(state, route, sources, tools);
            case CANCEL_ORDER, CONFIRM_RECEIVE, CREATE_AFTER_SALE -> {
                WriteOutcome write = prepareWrite(state, route, sources, tools);
                facts = write.facts();
                status = write.status();
                answer = write.answer();
                pendingActionId = write.pendingActionId();
            }
            default -> facts = "";
        }

        traceService.finishStep(stepId, status, "tools=" + tools + ", facts=" + preview(facts),
                System.currentTimeMillis() - start, null);

        Map<String, Object> out = new HashMap<>();
        out.put("facts", facts == null ? "" : facts);
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

    private Map<String, Object> answerNode(AgentGraphState state) {
        if (!"OK".equals(state.status())) {
            return Map.of();
        }
        RunContextRegistry.RunContext ctx = runContexts.get(state.runId());
        if (ctx == null) {
            return Map.of("answer", state.answer(), "status", "OK");
        }
        // 加购是写操作：成功结果由后端直接确认，不让模型改写事实（避免“已加购”被说成“没有该机型”）
        if (state.route() != null
                && state.route().action() == SupervisorAgent.Action.ADD_TO_CART
                && state.facts().contains("已成功为用户加入购物车")) {
            String answer = cartConfirmation(state.facts());
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            return Map.of("answer", answer, "status", "OK");
        }
        // 订单号不存在同样由后端给确定性结果，避免模型改写成模糊表述
        if (state.route() != null
                && state.route().action() == SupervisorAgent.Action.GET_ORDER
                && state.facts().contains("未找到订单")) {
            String answer = orderNotFound(state.facts());
            send(ctx.emitter(), "delta", Map.of("delta", answer));
            return Map.of("answer", answer, "status", "OK");
        }
        String system = promptFactory.systemPrompt(state.route().agent()) + "\n\n" + state.facts();
        List<Map<String, String>> history = historyOf(state.sessionId());
        String fallback = fallbackText(state);

        if (!llmClient.available()) {
            send(ctx.emitter(), "delta", Map.of("delta", fallback));
            return Map.of("answer", fallback, "status", "OK");
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
                    content.append(delta);
                    send(ctx.emitter(), "delta", Map.of("delta", guardPipeline.filterOutput(delta)));
                }
            });
            String answer = guardPipeline.filterOutput(
                    result.content().isBlank() ? fallback : result.content());
            answer = guardPipeline.stripPlanningPrefix(answer);
            if (content.isEmpty()) {
                // 网关把正文全放进思考流：补发兜底正文，保证前端有内容
                send(ctx.emitter(), "delta", Map.of("delta", fallback));
                answer = guardPipeline.filterOutput(fallback);
            }
            long latency = System.currentTimeMillis() - start;
            long tokens = result.promptTokens() + result.completionTokens();
            traceService.recordLlmCall(state.runId(), null, settingsModel(), promptFactory.promptVersion(state.route().agent()),
                    (int) result.promptTokens(), (int) result.completionTokens(), latency,
                    "stop", "SUCCESS", preview(system), preview(answer));
            return Map.of("answer", answer, "reasoning", reasoning.toString(), "status", "OK",
                    "totalTokens", tokens, "promptTokens", result.promptTokens(),
                    "completionTokens", result.completionTokens());
        } catch (Exception e) {
            log.warn("[cs] graph answer failed, fallback: {}", e.getMessage());
            send(ctx.emitter(), "delta", Map.of("delta", fallback));
            traceService.recordLlmCall(state.runId(), null, settingsModel(), promptFactory.promptVersion(state.route().agent()),
                    0, 0, System.currentTimeMillis() - start, "error", "FAILED", preview(system), preview(fallback));
            return Map.of("answer", fallback, "status", "OK");
        }
    }

    // ---------- 事实收集（通过 Tool Gateway） ----------

    private String searchProducts(AgentGraphState state, SupervisorAgent.RouteResult route,
                                  List<Map<String, Object>> cards, List<String> sources, List<String> tools) {
        String keyword = route.intent().keyword();
        if (keyword == null || keyword.isBlank()) {
            keyword = IntentRouter.extractKeyword(state.userText());
        }
        ToolResult result = tool(state, route, "searchProducts", Map.of("keyword", keyword, "limit", MAX_PRODUCTS), tools);
        List<RetrievalService.ProductHit> hits = new ArrayList<>(dataList(result, RetrievalService.ProductHit.class));
        // 对比/多品类查询：按“和/与/对比/哪个好”拆段各检索一次，避免只命中其中一款
        if (state.userText().matches(".*(和|与|对比|哪个好|哪个更|vs|VS).*")) {
            Map<Long, RetrievalService.ProductHit> merged = new LinkedHashMap<>();
            hits.forEach(hit -> merged.put(hit.card().spuId(), hit));
            for (String part : state.userText().split("(?:和|与|对比|哪个好|哪个更|vs|VS)")) {
                String partKeyword = IntentRouter.extractKeyword(part);
                if (partKeyword.isBlank() || partKeyword.equals(keyword)) {
                    continue;
                }
                ToolResult extra = tool(state, route, "searchProducts",
                        Map.of("keyword", partKeyword, "limit", 2), tools);
                dataList(extra, RetrievalService.ProductHit.class)
                        .forEach(hit -> merged.putIfAbsent(hit.card().spuId(), hit));
            }
            hits = new ArrayList<>(merged.values());
            if (hits.size() > MAX_PRODUCTS) {
                hits = new ArrayList<>(hits.subList(0, MAX_PRODUCTS));
            }
        }
        if (!hits.isEmpty()) {
            List<ProductCardDTO> hitCards = new ArrayList<>();
            for (RetrievalService.ProductHit hit : hits) {
                ProductCardDTO card = hit.card();
                hitCards.add(card);
                cards.add(cardMap(card));
                // vector/lexical 前缀与 V3.0 评估口径一致
                sources.add(hit.source() + ":" + card.spuId());
            }
            traceService.recordRetrieval(state.runId(), keyword, MAX_PRODUCTS,
                    hits.stream().map(hit -> (Object) hit.card().spuId()).toList(),
                    hits.stream().map(hit -> (Object) hit.score()).toList(),
                    0, true, "product");
            return productFacts(hitCards);
        }
        traceService.recordRetrieval(state.runId(), keyword, MAX_PRODUCTS,
                List.of(), List.of(), 0, false, "product");
        List<ProductCardDTO> previous = lastRecommendedCards(state.sessionId());
        if (!previous.isEmpty()) {
            for (ProductCardDTO card : previous) {
                cards.add(cardMap(card));
                sources.add("context:" + card.spuId());
            }
            return "（本轮未检索到新商品；以下为此前推荐的真实商品，围绕它们回答，禁止编造其它商品）\n"
                    + productFacts(previous);
        }
        return "（商品检索无结果：如实告知未找到匹配商品，可建议换个说法或去分类页逛逛）";
    }

    private String stockFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
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
            return "（该商品暂无可售规格：如实告知，建议关注补货或转人工）";
        }
        return skuFacts(product, skus);
    }

    private String addToCart(AgentGraphState state, SupervisorAgent.RouteResult route,
                             List<Map<String, Object>> cards, List<String> sources, List<String> tools) {
        ProductCardDTO product = resolveProduct(state.sessionId(), state.userText(), route.productRef());
        if (product == null) {
            return askWhichProduct(state, cards, sources);
        }
        cards.add(cardMap(product));
        ToolResult stock = tool(state, route, "getStock", Map.of("spuId", product.spuId()), tools);
        List<ProductSkuDTO> skus = dataList(stock, ProductSkuDTO.class);
        if (skus.isEmpty()) {
            return "（该商品暂无可售规格，无法加购：如实告知）";
        }
        ProductSkuDTO chosen = pickSku(skus, state.userText());
        if (chosen == null) {
            sources.add("sku:" + product.spuId());
            return "（该商品有多个规格，用户未说明要哪个：请列出以下规格让用户选择，不要自行加购）\n"
                    + skuFacts(product, skus);
        }
        int qty = Math.max(1, Math.min(route.quantity(), Math.max(chosen.availableStock(), 1)));
        ToolResult result = tool(state, route, "addToCart",
                Map.of("skuId", chosen.skuId(), "quantity", qty), tools);
        if (!result.success()) {
            return "（加购失败：" + result.errorMessage() + "。如实告知用户失败原因）";
        }
        sources.add("cart:" + chosen.skuId());
        return "（已成功为用户加入购物车：商品「" + product.name() + "」，规格「" + chosen.title()
                + "」，单价 " + chosen.price() + " 元，本次加入 " + qty + " 件。告知用户已加购，可到购物车结算）";
    }

    private String orderFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
                              List<String> sources, List<String> tools) {
        String orderNo = route.orderNo();
        Map<String, Object> args = orderNo == null || orderNo.isBlank()
                ? Map.of() : Map.of("orderNo", orderNo);
        ToolResult result = tool(state, route, "getOrder", args, tools);
        if (!result.success()) {
            if (orderNo != null && !orderNo.isBlank()) {
                sources.add("order:" + orderNo);
            }
            return "（" + result.errorMessage() + "：如实告知，并提醒核对订单号）";
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
            return sb.append("\n").toString();
        }
        List<OrderBriefDTO> orders = dataList(result, OrderBriefDTO.class);
        sources.add("order:list");
        if (orders.isEmpty()) {
            return "（该会员名下没有订单：如实告知）";
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
        return sb.toString();
    }

    private String knowledgeFacts(AgentGraphState state, SupervisorAgent.RouteResult route,
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
        return sb.toString();
    }

    private WriteOutcome prepareWrite(AgentGraphState state, SupervisorAgent.RouteResult route,
                                      List<String> sources, List<String> tools) {
        String orderNo = route.orderNo();
        ToolResult check = tool(state, route, "getOrder", Map.of("orderNo", orderNo), tools);
        if (!check.success()) {
            return new WriteOutcome("（" + check.errorMessage() + "：如实告知，并提醒核对订单号）",
                    "OK", null, null);
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
            return new WriteOutcome("（不支持的操作）", "FAILED", null, null);
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
                "NEED_CONFIRMATION", "即将" + summary + "。请确认是否执行。", action.getId());
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
        for (ProductCardDTO card : previous) {
            String head = card.name().length() > 4 ? card.name().substring(0, 4) : card.name();
            if (text.contains(head) || (ref.length() >= 2 && card.name().contains(ref))) {
                return card;
            }
        }
        if (ref.matches("\\d+")) {
            List<ProductCardDTO> named = productQueryApi.getBySpuIds(List.of(Long.parseLong(ref)));
            if (!named.isEmpty()) {
                return named.get(0);
            }
        }
        // 用户点名了目录里的其它商品（如“那 iPhone 呢”）：优先于“上文唯一”的代词兜底
        String keyword = IntentRouter.extractKeyword(text);
        List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(keyword, 5);
        for (RetrievalService.ProductHit hit : hits) {
            ProductCardDTO card = hit.card();
            String name = card.name();
            String head = name.length() > 4 ? name.substring(0, 4) : name;
            // 关键词直接命中商品名（“来三个收纳盒” → 抽屉式收纳盒）；避免品牌前缀导致前 4 字匹配失败
            boolean keywordHit = keyword.length() >= 2
                    && (name.contains(keyword) || keyword.contains(name));
            if (keywordHit || text.contains(head) || (ref.length() >= 2 && name.contains(ref))) {
                return card;
            }
        }
        if (!previous.isEmpty() && (ref.isBlank() || ref.matches(".*(它|这个|那个|刚才|上面|这款|同款).*"))) {
            return previous.size() == 1 ? previous.get(0) : null;
        }
        return hits.size() == 1 ? hits.get(0).card() : null;
    }

    private String askWhichProduct(AgentGraphState state, List<Map<String, Object>> cards, List<String> sources) {
        List<ProductCardDTO> previous = lastRecommendedCards(state.sessionId());
        if (previous.isEmpty()) {
            return "（用户想操作商品但当前会话没有可指代的商品：请用户说明具体是哪款，或先为其推荐）";
        }
        StringBuilder sb = new StringBuilder("（用户指代不明：请列出以下此前推荐过的商品，请用户确认后再继续，禁止瞎猜）\n");
        sources.add("context-ask");
        for (ProductCardDTO card : previous) {
            cards.add(cardMap(card));
            sb.append("【商品】").append(card.name()).append("（").append(card.price()).append(" 元）\n");
        }
        return sb.toString();
    }

    private ProductSkuDTO pickSku(List<ProductSkuDTO> skus, String userText) {
        String text = userText == null ? "" : userText;
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
            return spuIds.isEmpty() ? List.of() : productQueryApi.getBySpuIds(spuIds);
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
        String facts = state.facts();
        if (facts == null || facts.isBlank()) {
            return "智能助手暂时繁忙，请稍后再试或转人工。";
        }
        AgentName agent = state.route() == null ? AgentName.KNOWLEDGE : state.route().agent();
        return switch (agent) {
            case ORDER -> "已为您查询到订单信息：\n" + facts;
            case AFTER_SALE, KNOWLEDGE -> facts;
            default -> "根据检索结果为您推荐：\n" + facts;
        };
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
