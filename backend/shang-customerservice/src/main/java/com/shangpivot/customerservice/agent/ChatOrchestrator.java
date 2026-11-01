package com.shangpivot.customerservice.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.CartAddResultDTO;
import com.shangpivot.commerce.api.CartMutationApi;
import com.shangpivot.commerce.api.OrderBriefDTO;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsAuditLog;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.llm.LlmClient;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import com.shangpivot.customerservice.service.ChatSessionService;
import com.shangpivot.customerservice.service.CsAuditService;
import com.shangpivot.customerservice.service.CsGuardRailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段编排（ADR-0005）：意图与槽位 → 工具/检索执行 → 流式生成 → 输出护栏 → SSE 下发 → 异步落库。
 * 降级链（ADR-0006/PRD §8.13）：LLM 不可用时按意图给兜底话术 + 词法推荐，会话不中断。
 * 对抗性审查增量：SPECS/ADDCART 工具、多轮商品上下文、会话互斥锁释放、自动摘要、token 审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private static final int MAX_HISTORY = 8;
    private static final int MAX_PRODUCTS = 3;

    private final LlmClient llmClient;
    private final LlmSettingsResolver settingsResolver;
    private final IntentRouter intentRouter;
    private final GuardPipeline guardPipeline;
    private final RetrievalService retrievalService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final OrderQueryApi orderQueryApi;
    private final ProductQueryApi productCardsApi;
    private final ProductSkuApi productSkuApi;
    private final CartMutationApi cartMutationApi;
    private final CsGuardRailService guardRailService;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;
    private final CsAuditService auditService;
    private final CsMessageMapper messageMapper;

    /** 编排一次应答；在 csAgentExecutor 线程上执行。controller 已保存用户消息并取得会话锁。 */
    public void respond(SseEmitter emitter, ChatSession session, CsMessage userMsg) {
        String userText = userMsg.getContent() == null ? "" : userMsg.getContent();
        IntentRouter.Intent intent = null;
        try {
            GuardPipeline.GuardResult guard = guardPipeline.inspectInput(userText);
            if (guard.injectionSuspected()) {
                auditService.record("injection_detected", CsAuditLog.RISK_MID, session.getId(),
                        session.getCustomerId(), Map.of("preview", preview(userText)));
            }

            intent = intentRouter.route(userText);
            auditService.record("intent", CsAuditLog.RISK_LOW, session.getId(), session.getCustomerId(),
                    Map.of("intent", intent.type().name(), "byRule", intent.byRule()));

            Facts facts = collectFacts(intent, session, userText);

            if (!llmClient.available()) {
                degrade(emitter, session, intent, facts, "LLM 未配置");
                return;
            }

            String systemPrompt = settingsResolver.resolve().getSystemPrompt() + "\n\n" + facts.block();
            List<Map<String, String>> history = historyOf(session.getId());

            StringBuilder reasoning = new StringBuilder();
            StringBuilder content = new StringBuilder();
            LlmClient.StreamResult result = llmClient.streamChat(systemPrompt, history, new LlmClient.StreamListener() {
                @Override
                public void onReasoning(String delta) {
                    reasoning.append(delta);
                    send(emitter, "reasoning", Map.of("delta", delta));
                }

                @Override
                public void onContent(String delta) {
                    content.append(delta);
                    send(emitter, "delta", Map.of("delta", guardPipeline.filterOutput(delta)));
                }
            });

            String answer = guardPipeline.filterOutput(
                    result.content().isBlank() ? "抱歉，我暂时没有生成回答，请重试或转人工。" : result.content());
            // 思考残留兜底剥离（网关混流时正文开头可能带规划语）
            answer = guardPipeline.stripPlanningPrefix(answer);
            // 正文为空兜底（个别网关把内容全部放进思考流）
            if (content.isEmpty() && !result.reasoning().isBlank()) {
                answer = guardPipeline.filterOutput(summarizeFallback(intent, facts));
            }
            send(emitter, "delta", Map.of("delta", "")); // 结束占位，保证前端时序
            emitCards(emitter, facts.cards());

            Map<String, Object> metadata = facts.cards().isEmpty() ? null : Map.of(CsMessage.META_ITEMS, facts.cards());
            CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                    answer, result.reasoning(), metadata, facts.trace(), false);
            Map<String, Object> auditDetail = new java.util.HashMap<>();
            auditDetail.put("intent", intent.type().name());
            auditDetail.put("degraded", false);
            auditDetail.put("sources", facts.trace().getOrDefault("sources", List.of()));
            if (result.promptTokens() > 0 || result.completionTokens() > 0) {
                auditDetail.put("tokens", Map.of("prompt", result.promptTokens(), "completion", result.completionTokens()));
            }
            auditService.record("ai_reply", CsAuditLog.RISK_LOW, session.getId(), session.getCustomerId(), auditDetail);
            chatSessionService.autoSummarize(session.getId());
            Map<String, Object> doneData = new java.util.HashMap<>();
            doneData.put("messageId", saved.getId());
            doneData.put("sessionStatus", session.getStatus());
            doneData.put("degraded", false);
            doneData.put("sources", facts.trace().getOrDefault("sources", List.of()));
            if (result.promptTokens() > 0 || result.completionTokens() > 0) {
                doneData.put("tokens", Map.of("prompt", result.promptTokens(), "completion", result.completionTokens()));
            }
            send(emitter, "done", doneData);
        } catch (LlmClient.LlmException e) {
            log.warn("[cs] LLM 调用失败，走降级: {}", e.getMessage());
            auditService.record("degraded_reply", CsAuditLog.RISK_MID, session.getId(), session.getCustomerId(),
                    Map.of("reason", String.valueOf(e.getMessage()).substring(0, Math.min(120, String.valueOf(e.getMessage()).length()))));
            degrade(emitter, session, intent == null
                    ? new IntentRouter.Intent(IntentRouter.Type.PRESALE, IntentRouter.extractKeyword(userText), null, "", 1, true)
                    : intent, null, e.getMessage());
        } catch (Exception e) {
            log.error("[cs] 编排异常", e);
            send(emitter, "error", Map.of("code", 50000, "msg", "智能客服开小差了，请重试"));
            send(emitter, "done", Map.of("messageId", 0, "sessionStatus", session.getStatus(), "degraded", true));
        } finally {
            guardRailService.unlockSession(session.getId());
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
    }

    // ---------- 事实收集 ----------

    private record Facts(String block, List<Map<String, Object>> cards, Map<String, Object> trace) {
        Facts {
            if (cards == null) {
                cards = List.of();
            }
            if (trace == null) {
                trace = Map.of();
            }
        }
    }

    private Facts collectFacts(IntentRouter.Intent intent, ChatSession session, String userText) {
        return switch (intent.type()) {
            case PRESALE -> presaleFacts(session.getId(), intent.keyword() == null || intent.keyword().isBlank()
                    ? IntentRouter.extractKeyword(userText) : intent.keyword());
            case ORDER -> orderFacts(session, intent.orderNo());
            case AFTERSALE -> afterSaleFacts(userText);
            case KNOWLEDGE -> knowledgeFacts(userText);
            case SPECS -> specsFacts(session, userText, intent.productRef());
            case ADDCART -> addCartFacts(session, userText, intent.productRef(), intent.quantity());
            case CHITCHAT -> new Facts("", List.of(), Map.of("sources", List.of()));
        };
    }

    private Facts presaleFacts(Long sessionId, String keyword) {
        List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(keyword, MAX_PRODUCTS);
        List<ProductCardDTO> previous = lastRecommendedCards(sessionId);

        // 追问场景：本轮检索无有效命中，但此前推荐过商品 → 围绕上文商品回答
        if (hits.isEmpty()) {
            if (!previous.isEmpty()) {
                StringBuilder sb = new StringBuilder("（本轮未检索到新商品；用户很可能在追问此前推荐过的商品。以下为此前推荐的真实在售商品，围绕它们回答，禁止编造其它商品或规格；卡片信息之外的具体规格建议用户进商品详情页查看）：\n");
                List<Map<String, Object>> cards = new ArrayList<>();
                List<Object> sources = new ArrayList<>();
                int i = 1;
                for (ProductCardDTO c : previous) {
                    sb.append("【商品").append(i).append("】").append(c.name());
                    if (c.subtitle() != null && !c.subtitle().isBlank()) {
                        sb.append("（").append(c.subtitle()).append("）");
                    }
                    sb.append("，价格 ").append(c.price() == null ? "询价" : c.price()).append("\n");
                    cards.add(cardMap(c, i));
                    sources.add(Map.of("spuId", c.spuId(), "source", "context"));
                    i++;
                }
                return new Facts(sb.toString(), cards, Map.of("sources", sources));
            }
            return new Facts("（商品检索无结果：如实告知未找到匹配商品，可建议换个说法或去分类页逛逛）", List.of(), Map.of("sources", List.of("lexical:none")));
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        StringBuilder sb = new StringBuilder("以下是可推荐的真实在售商品（只能推荐这些，禁止编造）：\n");
        int i = 1;
        List<Object> sources = new ArrayList<>();
        for (RetrievalService.ProductHit hit : hits) {
            ProductCardDTO c = hit.card();
            sb.append("【商品").append(i).append("】").append(c.name());
            if (c.subtitle() != null && !c.subtitle().isBlank()) {
                sb.append("（").append(c.subtitle()).append("）");
            }
            sb.append("，价格 ").append(c.price() == null ? "询价" : c.price());
            if (c.priceMax() != null) {
                sb.append("~").append(c.priceMax());
            }
            sb.append("，已售 ").append(c.soldCount() == null ? 0 : c.soldCount()).append("\n");
            cards.add(cardMap(c, i));
            sources.add(Map.of("spuId", c.spuId(), "source", hit.source()));
            i++;
        }
        // 上文推荐过的商品以文字形式附给模型，供「刚才那款/第一个」等追问指代
        if (!previous.isEmpty()) {
            sb.append("此前对话推荐过的商品（供追问指代，不要重复推荐）：");
            for (ProductCardDTO c : previous) {
                sb.append(c.name()).append("（").append(c.price() == null ? "询价" : c.price()).append(" 元）；");
            }
            sb.append("\n");
        }
        return new Facts(sb.toString(), cards, Map.of("sources", sources));
    }

    /** 规格追问：解析用户指代的上文商品 → 拉取真实 SKU 规格（含库存）。 */
    private Facts specsFacts(ChatSession session, String userText, String productRef) {
        ProductCardDTO target = resolveContextProduct(session.getId(), userText, productRef);
        if (target == null) {
            List<ProductCardDTO> previous = lastRecommendedCards(session.getId());
            if (!previous.isEmpty()) {
                // 指代不明：列出上文商品让用户确认，绝不瞎猜
                StringBuilder sb = new StringBuilder("（用户在问规格但没说清是哪一款：请列出以下此前推荐过的商品，请用户确认后再答）：\n");
                List<Map<String, Object>> cards = new ArrayList<>();
                int i = 1;
                for (ProductCardDTO c : previous) {
                    sb.append("【商品").append(i).append("】").append(c.name()).append("（").append(c.price()).append(" 元）\n");
                    cards.add(cardMap(c, i));
                    i++;
                }
                return new Facts(sb.toString(), cards, Map.of("sources", List.of("context-ask")));
            }
            return new Facts("（用户在问商品规格，但当前会话没有可指代的商品上下文：请用户说明具体是哪款商品，或先描述需求）",
                    List.of(), Map.of("sources", List.of()));
        }
        List<ProductSkuDTO> skus = productSkuApi.listEnabledBySpu(target.spuId());
        if (skus.isEmpty()) {
            return new Facts("（该商品暂无可售规格：如实告知，建议关注补货或转人工）", List.of(), Map.of("sources", List.of()));
        }
        StringBuilder sb = new StringBuilder("用户询问的商品与其真实规格（如实引用，禁止编造）：\n");
        sb.append("商品：").append(target.name());
        if (target.subtitle() != null && !target.subtitle().isBlank()) {
            sb.append("（").append(target.subtitle()).append("）");
        }
        sb.append("\n可选规格：\n");
        int i = 1;
        for (ProductSkuDTO sku : skus) {
            sb.append("【规格").append(i).append("】").append(sku.title());
            if (sku.specs() != null && !sku.specs().isEmpty()) {
                sb.append("，参数：").append(sku.specs());
            }
            sb.append("，价格 ").append(sku.price()).append(" 元，库存 ").append(sku.availableStock() <= 0 ? "紧张/无货" : sku.availableStock()).append("\n");
            i++;
        }
        sb.append("（用户若问卡片未覆盖的参数，请如实说明需进详情页或转人工）\n");
        List<Map<String, Object>> cards = List.of(cardMap(target, 1));
        return new Facts(sb.toString(), cards, Map.of("sources", List.of("sku:" + target.spuId())));
    }

    /** 对话内加购：解析目标商品与规格 → 调 commerce 加购 → 告知结果。 */
    private Facts addCartFacts(ChatSession session, String userText, String productRef, int quantity) {
        ProductCardDTO target = resolveContextProduct(session.getId(), userText, productRef);
        if (target == null) {
            List<ProductCardDTO> previous = lastRecommendedCards(session.getId());
            if (!previous.isEmpty()) {
                StringBuilder sb = new StringBuilder("（用户想购买但没说清是哪一款：请列出以下此前推荐过的商品，请用户确认后再加购）：\n");
                List<Map<String, Object>> cards = new ArrayList<>();
                int i = 1;
                for (ProductCardDTO c : previous) {
                    sb.append("【商品").append(i).append("】").append(c.name()).append("（").append(c.price()).append(" 元）\n");
                    cards.add(cardMap(c, i));
                    i++;
                }
                return new Facts(sb.toString(), cards, Map.of("sources", List.of("context-ask")));
            }
            return new Facts("（用户想购买，但当前会话没有可指代的商品：请用户说明要哪款，或先为其推荐）",
                    List.of(), Map.of("sources", List.of()));
        }
        List<ProductSkuDTO> skus = productSkuApi.listEnabledBySpu(target.spuId());
        if (skus.isEmpty()) {
            return new Facts("（该商品暂无可售规格，无法加购：如实告知）", List.of(), Map.of("sources", List.of()));
        }
        ProductSkuDTO chosen = pickSku(skus, userText);
        if (chosen == null) {
            StringBuilder sb = new StringBuilder("（该商品有多个规格，用户未说明要哪个：请列出以下规格让用户选择，不要自行加购）：\n");
            int i = 1;
            for (ProductSkuDTO sku : skus) {
                sb.append("【规格").append(i).append("】").append(sku.title()).append("，").append(sku.price()).append(" 元\n");
                i++;
            }
            List<Map<String, Object>> cards = List.of(cardMap(target, 1));
            return new Facts(sb.toString(), cards, Map.of("sources", List.of("sku-ask:" + target.spuId())));
        }
        int qty = Math.max(1, Math.min(quantity, Math.max(chosen.availableStock(), 1)));
        try {
            CartAddResultDTO added = cartMutationApi.addToCart(session.getCustomerId(), chosen.skuId(), qty);
            String text = "（已成功为用户加入购物车：商品「" + target.name() + "」，规格「" + chosen.title()
                    + "」，单价 " + chosen.price() + " 元，本次加入 " + qty + " 台，购物车现有 " + added.quantity()
                    + " 台。告知用户已加购（引用本次数量），可到购物车结算；如还需其它商品可继续说）";
            auditService.record("tool_add_cart", CsAuditLog.RISK_LOW, session.getId(), session.getCustomerId(),
                    Map.of("spuId", target.spuId(), "skuId", chosen.skuId(), "qty", added.quantity()));
            List<Map<String, Object>> cards = List.of(cardMap(target, 1));
            return new Facts(text, cards, Map.of("sources", List.of("cart:" + chosen.skuId())));
        } catch (BizException e) {
            return new Facts("（加购失败：" + e.getMessage() + "。如实告知用户失败原因）",
                    List.of(), Map.of("sources", List.of("cart-fail")));
        }
    }

    /** 解析用户指代的商品：显式序号/商品名 → 上文卡片；指代不明且上文唯一 → 上文；否则 null（由调用方反问用户，禁止瞎检索）。 */
    private ProductCardDTO resolveContextProduct(Long sessionId, String userText, String productRef) {
        List<ProductCardDTO> previous = lastRecommendedCards(sessionId);
        String ref = productRef == null ? "" : productRef.trim();
        String text = userText == null ? "" : userText;

        // 1) 序号指代
        java.util.regex.Matcher idx = java.util.regex.Pattern.compile("第\\s*([一二三四五1-5])").matcher(ref + " " + text);
        if (idx.find()) {
            int n = "一二三四五".indexOf(idx.group(1));
            int zero = n >= 0 ? n : (Integer.parseInt(idx.group(1)) - 1);
            if (zero >= 0 && zero < previous.size()) {
                return previous.get(zero);
            }
        }
        // 2) 商品名匹配（上文卡片名 or 指代词本身含商品名片段）
        for (ProductCardDTO c : previous) {
            String head = c.name().length() > 4 ? c.name().substring(0, 4) : c.name();
            if (text.contains(head) || (ref.length() >= 2 && c.name().contains(ref))) {
                return c;
            }
        }
        // 3) 指代词或空：上文唯一则取唯一；多个上文 + 模糊指代 → null（反问用户，绝不拿垃圾检索凑数）
        if (!previous.isEmpty() && (ref.isBlank() || ref.matches(".*(它|这个|那个|刚才|上面|这款|同款).*"))) {
            return previous.size() == 1 ? previous.get(0) : null;
        }
        // 4) 完全无上文：按消息内容检索一次（此时没有更好的依据）
        if (previous.isEmpty()) {
            List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(
                    IntentRouter.extractKeyword(text), 1);
            return hits.isEmpty() ? null : hits.get(0).card();
        }
        return null;
    }

    /** 选 SKU：消息里点名规格 → 命中；单一规格 → 直选；否则 null（反问用户）。 */
    private ProductSkuDTO pickSku(List<ProductSkuDTO> skus, String userText) {
        String text = userText == null ? "" : userText;
        for (ProductSkuDTO sku : skus) {
            if (text.contains(sku.title())) {
                return sku;
            }
            if (sku.specs() != null) {
                for (Object v : sku.specs().values()) {
                    String s = String.valueOf(v);
                    if (s.length() >= 2 && text.contains(s)) {
                        return sku;
                    }
                }
            }
        }
        return skus.size() == 1 ? skus.get(0) : null;
    }

    /** 会话内最近一条带商品卡片的助手消息（即上一轮推荐结果）。 */
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
            return productCardsApi.getBySpuIds(spuIds);
        } catch (Exception e) {
            log.debug("[cs] 读取上文推荐商品失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Facts orderFacts(ChatSession session, String orderNo) {
        StringBuilder sb = new StringBuilder("当前会员的订单数据（如实引用）：\n");
        if (orderNo != null && !orderNo.isBlank()) {
            OrderTraceDTO t = orderQueryApi.getTrace(session.getCustomerId(), orderNo);
            if (t == null) {
                return new Facts("（未找到订单 " + orderNo + "：如实告知，并提醒核对订单号）", List.of(), Map.of("sources", List.of()));
            }
            sb.append("订单 ").append(t.orderNo()).append("，状态 ").append(statusText(t.status()))
                    .append("，实付 ").append(t.payAmount() == null ? "-" : t.payAmount()).append(" 元");
            if (t.carrier() != null) {
                sb.append("；物流：").append(t.carrier()).append(" ").append(t.trackingNo() == null ? "" : t.trackingNo())
                        .append("（").append(t.shipmentStatus() == null ? "-" : t.shipmentStatus()).append("）");
                if (t.traces() != null && !t.traces().isEmpty()) {
                    sb.append("；最近轨迹：").append(trailText(t.traces()));
                }
            } else {
                sb.append("；暂无发货记录");
            }
            sb.append("\n");
            return new Facts(sb.toString(), List.of(), Map.of("sources", List.of("order:" + t.orderNo())));
        }
        PageResult<OrderBriefDTO> page = orderQueryApi.pageByCustomer(session.getCustomerId(), 1, 3);
        if (page.getRecords().isEmpty()) {
            return new Facts("（该会员名下没有订单：如实告知）", List.of(), Map.of("sources", List.of("order:list")));
        }
        for (OrderBriefDTO o : page.getRecords()) {
            sb.append("订单 ").append(o.orderNo()).append("，状态 ").append(statusText(o.status()))
                    .append("，实付 ").append(o.payAmount() == null ? "-" : o.payAmount()).append(" 元");
            if (o.shipAt() != null) {
                sb.append("，已发货");
            }
            sb.append("\n");
        }
        return new Facts(sb.toString(), List.of(), Map.of("sources", List.of("order:list")));
    }

    private Facts afterSaleFacts(String userText) {
        var hits = knowledgeSearchService.search("退货 换货 售后 " + userText, 2);
        StringBuilder sb = new StringBuilder("售后与知识资料（如实引用，禁止编造政策）：\n");
        for (var h : hits) {
            sb.append("《").append(h.title()).append("》：").append(h.snippet()).append("\n");
        }
        if (hits.isEmpty()) {
            sb.append("（无匹配知识；引导用户在 App 内「我的订单 → 申请售后」操作，或转人工）\n");
        }
        return new Facts(sb.toString(), List.of(), Map.of("sources", hits.stream().map(h -> "doc:" + h.docId()).toList()));
    }

    private Facts knowledgeFacts(String userText) {
        var hits = knowledgeSearchService.search(userText, 2);
        StringBuilder sb = new StringBuilder("知识资料（如实引用，禁止编造；引用《标题》）：\n");
        for (var h : hits) {
            sb.append("《").append(h.title()).append("》：").append(h.snippet()).append("\n");
        }
        if (hits.isEmpty()) {
            sb.append("（无匹配知识：如实告知，建议换问法或转人工）\n");
        }
        return new Facts(sb.toString(), List.of(), Map.of("sources", hits.stream().map(h -> "doc:" + h.docId()).toList()));
    }

    // ---------- 降级 ----------

    private void degrade(SseEmitter emitter, ChatSession session, IntentRouter.Intent intent,
                         Facts facts, String reason) {
        String text;
        List<Map<String, Object>> cards = List.of();
        switch (intent.type()) {
            case PRESALE -> {
                List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(
                        intent.keyword() == null ? "" : intent.keyword(), MAX_PRODUCTS);
                if (!hits.isEmpty()) {
                    List<Map<String, Object>> built = new ArrayList<>();
                    for (int i = 0; i < hits.size(); i++) {
                        built.add(cardMap(hits.get(i).card(), i + 1));
                    }
                    cards = built;
                    text = "智能助手暂时繁忙，先为您找到这些商品：\n" + hits.stream()
                            .map(h -> "· " + h.card().name() + "（" + h.card().price() + " 元起）")
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                } else {
                    text = "智能助手暂时繁忙，请稍后再试，或去分类页逛逛~";
                }
            }
            case ORDER -> text = "订单查询暂时不可用，您可以在「我的 → 我的订单」中查看，稍后我再为您查询。";
            case AFTERSALE, KNOWLEDGE -> text = "智能助手暂时繁忙。退货可在「我的订单 → 申请售后」发起，也可点击右上角转人工。";
            default -> text = "智能助手暂时繁忙，请稍后再试~";
        }
        CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                text, null, cards.isEmpty() ? null : Map.of(CsMessage.META_ITEMS, cards), null, true);
        send(emitter, "reasoning", Map.of("delta", ""));
        send(emitter, "delta", Map.of("delta", text));
        emitCards(emitter, cards);
        send(emitter, "done", Map.of("messageId", saved.getId(), "sessionStatus", session.getStatus(),
                "degraded", true, "sources", List.of()));
    }

    private String summarizeFallback(IntentRouter.Intent intent, Facts facts) {
        return switch (intent.type()) {
            case ORDER -> "已为您查询到订单信息：" + facts.block();
            case KNOWLEDGE, AFTERSALE -> facts.block();
            default -> "根据检索结果为您推荐：\n" + facts.block();
        };
    }

    // ---------- 工具 ----------

    private List<Map<String, String>> historyOf(Long sessionId) {
        List<CsMessage> latest = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, sessionId)
                .orderByDesc(CsMessage::getId)
                .last("LIMIT " + MAX_HISTORY));
        List<Map<String, String>> history = new ArrayList<>();
        for (CsMessage m : latest.reversed()) {
            String role = CsMessage.ROLE_USER.equals(m.getRole()) ? "user" : "assistant";
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.isBlank()) {
                continue;
            }
            history.add(Map.of("role", role, "content", content));
        }
        return history;
    }

    private void emitCards(SseEmitter emitter, List<Map<String, Object>> cards) {
        if (!cards.isEmpty()) {
            send(emitter, "cards", Map.of(CsMessage.META_ITEMS, cards));
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("[cs] SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private Map<String, Object> cardMap(ProductCardDTO c, int seq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("spuId", c.spuId());
        m.put("name", c.name());
        m.put("subtitle", c.subtitle());
        m.put("pic", c.pic());
        m.put("price", c.price());
        m.put("priceMax", c.priceMax());
        m.put("soldCount", c.soldCount());
        return m;
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

    @SuppressWarnings("unchecked")
    private static String trailText(List<Map<String, Object>> traces) {
        Map<String, Object> last = traces.get(traces.size() - 1);
        Object desc = last.get("desc") != null ? last.get("desc") : last.get("content");
        return desc == null ? "" : desc.toString();
    }

    private static String preview(String s) {
        return s == null ? "" : s.substring(0, Math.min(80, s.length()));
    }
}
