package com.shangpivot.customerservice.agent;

import com.shangpivot.commerce.api.OrderBriefDTO;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import com.shangpivot.customerservice.retrieval.RetrievalService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 事实直出渲染：商品/价格/库存/订单/政策全部来自后端结构化结果，不经过模型。
 * 高风险 route 的 displayAnswer 必须与 cards 来自同一份检索结果。
 */
public final class FactAnswerRenderer {

    private FactAnswerRenderer() {
    }

    public static String presale(String keyword, List<RetrievalService.ProductHit> hits,
                                 List<com.shangpivot.commerce.api.ProductCardDTO> previous,
                                 String budgetLabel, String budgetHint,
                                 boolean explicitNewQuery, String userText) {
        if (hits == null || hits.isEmpty()) {
            if (!explicitNewQuery && previous != null && !previous.isEmpty()) {
                return "本轮没有检索到新商品。之前为您推荐过：\n" + productLines(previous)
                        + "想了解哪一款的规格，或需要我帮您加购吗？";
            }
            return noProducts(keyword, userText);
        }
        StringBuilder sb = new StringBuilder();
        String privacy = privacyNotice(userText);
        if (!privacy.isBlank()) {
            sb.append(privacy).append('\n');
        }
        if (userText != null && (userText.contains("优惠") || userText.contains("折扣")
                || userText.contains("活动价") || userText.contains("便宜点"))) {
            sb.append("目前没有查询到专属优惠活动；以下为在售商品，具体活动价以商品详情页为准。\n");
        }
        // 有预算时本轮文案只展示预算内商品；超预算候选保留在 cards 中供多轮指代，
        // 文案的“版本取舍”会单独说明超预算可选款，避免把对比款说成推荐。
        List<RetrievalService.ProductHit> displayHits = hits;
        if (budgetLabel != null && !budgetLabel.isBlank()
                && hits.stream().anyMatch(RetrievalService.ProductHit::withinBudget)) {
            displayHits = hits.stream().filter(RetrievalService.ProductHit::withinBudget).toList();
        }
        sb.append("为您找到以下商品：\n");
        int i = 1;
        for (RetrievalService.ProductHit hit : displayHits) {
            ProductCardDTO card = hit.card();
            sb.append("【商品").append(i++).append("】").append(card.name());
            if (card.subtitle() != null && !card.subtitle().isBlank()) {
                sb.append("（").append(card.subtitle()).append("）");
            }
            sb.append("，价格 ").append(money(card.price())).append(" 元");
            if (card.priceMax() != null && card.price() != null
                    && card.priceMax().compareTo(card.price()) > 0) {
                sb.append("起");
            }
            if (!hit.withinBudget()) {
                sb.append("（超预算，仅作对比）");
            }
            sb.append("\n");
        }
        boolean hasOverBudgetDisplayed = displayHits.stream().anyMatch(h -> !h.withinBudget());
        if (budgetLabel != null && !budgetLabel.isBlank()) {
            sb.append("您的预算：").append(budgetLabel);
            if (hasOverBudgetDisplayed) {
                if (budgetHint != null && !budgetHint.isBlank()) {
                    sb.append("；").append(budgetHint);
                }
            } else if (budgetLabel.contains("左右")) {
                sb.append("；以下为接近预算的候选（价格在合理浮动范围内）");
            } else {
                sb.append("；以下商品在预算内");
            }
            sb.append("。\n");
        }
        String needs = needsText(userText);
        if (!needs.isBlank()) {
            long inBudget = hits.stream().filter(RetrievalService.ProductHit::withinBudget).count();
            String firstIn = hits.stream().filter(RetrievalService.ProductHit::withinBudget)
                    .findFirst().map(h -> "【" + h.card().name() + "】（" + money(h.card().price()) + " 元）")
                    .orElse("预算内候选");
            sb.append("版本取舍：\n");
            sb.append("- 预算优先：").append(firstIn);
            if (inBudget <= 1) {
                sb.append("（唯一符合预算的候选，价格更低）");
            } else {
                sb.append("（共 ").append(inBudget).append(" 款）");
            }
            sb.append("；本轮商品数据未提供").append(needs).append("的具体参数，无法确认是否完全满足，需进详情页核对。\n");
            String overBudget = hits.stream().filter(h -> !h.withinBudget())
                    .map(h -> "【" + h.card().name() + "】（" + money(h.card().price()) + " 元起）")
                    .reduce((a, b) -> a + "、" + b).orElse("");
            if (!overBudget.isBlank()) {
                sb.append("- 超预算：").append(overBudget)
                        .append(" 不纳入本次预算内推荐；仅在您主动提高预算时再考虑。\n");
            }
            sb.append("- 结论：如果预算优先，").append(firstIn).append(" 是当前优先候选；但如果")
                    .append(needs).append("是硬性条件，本轮目录无法确认其满足，"
                            + "建议暂缓下单并转人工核实参数，或等有明确参数的机型；"
                            + "不要为了未确认的参数直接超预算购买。\n");
        }
        sb.append("需要我帮您对比，或直接加入购物车吗？");
        return sb.toString();
    }

    public static String noProducts(String keyword) {
        return noProducts(keyword, null);
    }

    public static String noProducts(String keyword, String userText) {
        String privacy = privacyNotice(userText);
        if (userText != null && (userText.contains("优惠") || userText.contains("折扣")
                || userText.contains("便宜点") || userText.contains("活动价"))) {
            return (privacy.isBlank() ? "" : privacy + "\n")
                    + "目前没有查询到专属优惠活动。您可以告诉我想买的具体商品，我帮您看价格；"
                    + "也可以关注商品详情页的活动信息。";
        }
        String key = keyword == null || keyword.isBlank() ? "该品类" : "「" + keyword + "」";
        return (privacy.isBlank() ? "" : privacy + "\n")
                + "尚品汇目前没有找到与" + key + "匹配的商品。可以换个说法，或去分类页看看。";
    }

    public static boolean isPiiDiscount(String userText) {
        if (userText == null) {
            return false;
        }
        boolean pii = userText.contains("手机号") || userText.contains("****");
        boolean discount = userText.contains("优惠") || userText.contains("折扣")
                || userText.contains("活动价") || userText.contains("便宜点") || userText.contains("便宜些");
        return pii && discount;
    }

    private static String privacyNotice(String userText) {
        if (userText != null && (userText.contains("手机号") || userText.contains("****"))) {
            return "您的手机号已做隐私保护，不会完整展示。";
        }
        return "";
    }

    public static String previousProducts(List<ProductCardDTO> previous) {
        return "当前会话没有可操作的商品。之前为您推荐过：\n" + productLines(previous)
                + "请告诉我要哪一款。";
    }

    public static String noProductContext() {
        return "当前会话没有可操作的商品。请告诉我具体商品名称，或先让我为您推荐。";
    }

    public static String clarifyProducts(List<ProductCardDTO> previous) {
        if (previous == null || previous.isEmpty()) {
            return noProductContext();
        }
        StringBuilder sb = new StringBuilder("您指的是哪一款？请回复序号：\n");
        int i = 1;
        for (ProductCardDTO card : previous) {
            sb.append(i++).append(". ").append(card.name()).append("\n");
        }
        return sb.append("以上均为此前推荐过的商品；如需更具体的规格或价格，请先确认款式。").toString().trim();
    }

    public static String specs(ProductCardDTO product, List<ProductSkuDTO> skus) {
        if (skus == null || skus.isEmpty()) {
            return "《" + product.name() + "》暂无可售规格，建议关注补货或转人工。";
        }
        // 只展示与商品卡容量/标题一致的规格，避免“问 128G 却把 256G 也列出来”造成歧义。
        List<ProductSkuDTO> aligned = skus.stream()
                .filter(sku -> sku.title() != null && product.name().contains(sku.title()))
                .toList();
        if (!aligned.isEmpty()) {
            skus = aligned;
        }
        StringBuilder sb = new StringBuilder("您问的是《").append(baseName(product.name())).append("》。可选规格：\n");
        int i = 1;
        for (ProductSkuDTO sku : skus) {
            sb.append("【规格").append(i++).append("】").append(skuLabel(sku));
            sb.append("，价格 ").append(money(sku.price())).append(" 元，库存 ")
                    .append(sku.availableStock() <= 0 ? "紧张/无货" : sku.availableStock()).append("\n");
        }
        sb.append("未列出的参数可进商品详情页查看。");
        return sb.toString();
    }

    public static String addCartClarify(ProductCardDTO product, List<ProductSkuDTO> skus) {
        StringBuilder sb = new StringBuilder("《").append(baseName(product.name())).append("》有多个规格，请确认您要哪一款：\n");
        int i = 1;
        for (ProductSkuDTO sku : skus) {
            sb.append("【规格").append(i++).append("】").append(skuLabel(sku))
                    .append("，").append(money(sku.price())).append(" 元\n");
        }
        return sb.append("回复规格序号即可为您加购～").toString();
    }

    public static String addCartSuccess(ProductCardDTO product, ProductSkuDTO sku, int qty, Integer cartTotal) {
        StringBuilder sb = new StringBuilder("已为您加入购物车：商品「").append(product.name())
                .append("」，规格「").append(sku.title()).append("」，单价 ").append(money(sku.price()))
                .append(" 元，本次加入 ").append(qty).append(" 件");
        if (cartTotal != null && cartTotal > 0) {
            sb.append("，购物车现有 ").append(cartTotal).append(" 件");
        }
        return sb.append("。可到购物车结算～").toString();
    }

    public static String addCartFail(String message) {
        return "加购没有成功：" + (message == null || message.isBlank() ? "请稍后重试" : message)
                + "。您也可以转人工协助处理。";
    }

    public static String orderTrace(OrderTraceDTO trace) {
        StringBuilder sb = new StringBuilder("订单 ").append(trace.orderNo())
                .append(" 当前状态：").append(statusText(trace.status()))
                .append("，实付 ").append(trace.payAmount() == null ? "-" : trace.payAmount()).append(" 元。");
        if (trace.carrier() != null) {
            sb.append("物流：").append(trace.carrier()).append(" ")
                    .append(trace.trackingNo() == null ? "" : trace.trackingNo())
                    .append("（").append(trace.shipmentStatus() == null ? "-" : trace.shipmentStatus()).append("）");
            if (trace.traces() != null && !trace.traces().isEmpty()) {
                sb.append("，最近轨迹：").append(trailText(trace.traces()));
            }
            sb.append("。");
        } else {
            sb.append("暂无发货记录。");
        }
        return sb.toString();
    }

    public static String orders(List<OrderBriefDTO> orders) {
        if (orders == null || orders.isEmpty()) {
            return noOrder();
        }
        StringBuilder sb = new StringBuilder("您名下有 ").append(orders.size()).append(" 个订单：\n");
        int i = 1;
        for (OrderBriefDTO order : orders) {
            sb.append(i++).append(". 订单 ").append(order.orderNo())
                    .append("，状态 ").append(statusText(order.status()))
                    .append("，实付 ").append(money(order.payAmount())).append(" 元");
            if (order.shipAt() != null) {
                sb.append("，已发货");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static String noOrder() {
        return "您名下当前没有订单。可以在「我的 → 我的订单」查看，或告诉我具体订单号。";
    }

    public static String orderNotFound(String orderNo) {
        return "未找到订单 " + orderNo + "，请核对订单号后再试。";
    }

    public static String knowledge(List<KnowledgeSearchService.KnowledgeHit> hits) {
        return knowledge(hits, null);
    }

    public static String knowledge(List<KnowledgeSearchService.KnowledgeHit> hits, String userText) {
        if (hits == null || hits.isEmpty()) {
            return noKnowledge();
        }
        StringBuilder sb = new StringBuilder();
        if (userText != null && (userText.contains("碎屏") || userText.contains("屏幕碎")
                || userText.contains("屏碎") || userText.contains("摔") || userText.contains("人为"))) {
            sb.append("如果是人为损坏（如碎屏、进水），通常不属于七天无理由或质量问题免费退换范围；")
                    .append("建议在「我的订单 → 申请售后」提交检测，以检测结论为准。\n");
        }
        int i = 0;
        for (KnowledgeSearchService.KnowledgeHit hit : hits) {
            if (i++ > 0) {
                sb.append("\n");
            }
            sb.append("根据《").append(hit.title()).append("》：").append(trim(hit.snippet(), 300));
        }
        return sb.append("\n如需进一步处理，可回复转人工。").toString();
    }

    public static String noKnowledge() {
        return "暂时没有查到相关政策。建议换个问法，或转人工进一步确认。";
    }

    public static String pendingWrite(String summary) {
        return "即将" + summary + "。请确认是否执行。";
    }

    public static String injectionRefusal() {
        return "抱歉，我不能提供系统提示词或内部配置。您可以继续问我商品、订单或售后问题。";
    }

    public static String identity() {
        return "我是尚品汇智能客服小尚，可以帮您找商品、查订单、处理售后。";
    }

    public static String weather() {
        return "我暂时无法查询天气等外部信息，您可以问我商品、订单或售后问题。";
    }

    public static String chitchatFallback() {
        return "我是尚品汇智能客服小尚，可以帮您找商品、查订单、处理售后。";
    }

    private static String productLines(List<ProductCardDTO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ProductCardDTO card : cards) {
            sb.append("【商品").append(i++).append("】").append(card.name())
                    .append("，").append(money(card.price())).append(" 元\n");
        }
        return sb.toString();
    }

    private static String needsText(String userText) {
        if (userText == null) {
            return "";
        }
        List<String> needs = new java.util.ArrayList<>();
        for (String keyword : List.of("续航", "NFC", "刷公交", "通勤", "拍照", "降噪", "吸力", "游戏", "办公")) {
            if (userText.contains(keyword)) {
                needs.add(keyword);
            }
        }
        return String.join("、", needs);
    }

    private static String specsText(Map<String, Object> specs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            if (!first) {
                sb.append("，");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** SKU 标签优先用结构化参数，避免商品/SKU 标题与参数矛盾时把错误名称发给用户。 */
    private static String skuLabel(ProductSkuDTO sku) {
        if (sku.specs() == null || sku.specs().isEmpty()) {
            return sku.title();
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : sku.specs().entrySet()) {
            if (!first) {
                sb.append("，");
            }
            sb.append(entry.getKey()).append(" ").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** 去掉商品名末尾的容量/内存标记，避免“iPhone 15 128G”下挂 256GB 规格造成自相矛盾。 */
    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceFirst("\\s+\\d+\\s*(GB|G|TB|T)$", "");
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "询价";
        }
        return value.stripTrailingZeros().toPlainString();
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

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
