package com.shangpivot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.AfterSaleApi;
import com.shangpivot.commerce.api.CartAddResultDTO;
import com.shangpivot.commerce.api.CartMutationApi;
import com.shangpivot.commerce.api.OrderCommandApi;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.agent.GuardPipeline;
import com.shangpivot.customerservice.agent.graph.AgentGraphOrchestrator;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.eval.GoldenCase;
import com.shangpivot.customerservice.eval.GoldenDataset;
import com.shangpivot.customerservice.eval.ReferenceCartDataset;
import com.shangpivot.customerservice.llm.LlmClient;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * V3.1 图编排的 Golden 确定性回归（无真实 LLM）：
 * 把 GoldenDataset 的 20 条业务规则跑一遍，只断言 sources/cards/answer/forbid/效率/购物车，
 * 不跑 LLM 裁判。用于回答“graph 路径是否复现 V3.0 的业务约束”。
 */
@SpringBootTest
class GraphGoldenIntegrationTest {

    @Autowired
    private AgentGraphOrchestrator orchestrator;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private GuardPipeline guardPipeline;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmClient llmClient;
    @MockitoBean
    private LlmSettingsResolver settingsResolver;
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
    private KnowledgeSearchService knowledgeSearchService;
    @MockitoBean
    private RetrievalService retrievalService;
    @MockitoBean
    private CsGuardRailService guardRailService;

    private final Map<Long, Map<Long, Integer>> carts = new ConcurrentHashMap<>();

    private record Product(long spuId, String name, String subtitle, String category, BigDecimal price) {
        ProductCardDTO card() {
            return new ProductCardDTO(spuId, name, subtitle, null, price, null, 100L, category);
        }
    }

    /** 与生产种子目录（commerce.spu 中 status=ON 的 30 个 SPU）对齐，价格取当前 SKU 最低价。 */
    private static final List<Product> CATALOG = List.of(
            new Product(1L, "Redmi K70 12+256G", "12+256G", "手机", new BigDecimal("2299")),
            new Product(2L, "iPhone 15 128G", "128G", "手机", new BigDecimal("5999")),
            new Product(3L, "vivo X100", "12+256G", "手机", new BigDecimal("5299")),
            new Product(4L, "索尼 WH-1000XM5", "头戴降噪耳机", "耳机", new BigDecimal("2599")),
            new Product(5L, "AirPods Pro 2", "入耳降噪耳机", "耳机", new BigDecimal("1999")),
            new Product(6L, "戴森 V12 吸尘器", "无线吸尘器", "吸尘器", new BigDecimal("3999")),
            new Product(7L, "石头 扫拖机器人", "激光导航", "扫地机器人", new BigDecimal("2599")),
            new Product(8L, "双人帐篷 防雨款", "户外露营", "帐篷", new BigDecimal("599")),
            new Product(9L, "海尔 纤诺 洗衣机", "大容量变频", "洗衣机", new BigDecimal("3299")),
            new Product(10L, "iPhone 15 Pro", "128G", "手机", new BigDecimal("7999")),
            new Product(11L, "MacBook Air 13 M3 16G", "轻薄本", "电脑", new BigDecimal("9999")),
            new Product(12L, "Apple Watch SE", "智能手表", "手表", new BigDecimal("2199")),
            new Product(13L, "罗技 MX Master 3s", "人体工学鼠标", "外设", new BigDecimal("599")),
            new Product(14L, "Xiaomi 14 Pro", "12+256G", "手机", new BigDecimal("4999")),
            new Product(15L, "Anker 65W 充电器", "氮化镓快充", "配件", new BigDecimal("199")),
            new Product(16L, "HUAWEI Mate X5", "折叠旗舰，大屏体验", "手机", new BigDecimal("12999")),
            new Product(17L, "OPPO Find N3", "折叠轻薄，旗舰体验", "手机", new BigDecimal("9999")),
            new Product(18L, "荣耀 Magic6 Pro", "骁龙8 Gen3 旗舰", "手机", new BigDecimal("6499")),
            new Product(19L, "ThinkPad X1 Carbon", "高可靠商务轻薄", "电脑", new BigDecimal("12999")),
            new Product(20L, "ROG 枪神 笔记本", "高刷电竞游戏本", "电脑", new BigDecimal("14999")),
            new Product(21L, "联想 拯救者 Y7000", "高性价比游戏本", "电脑", new BigDecimal("6999")),
            new Product(22L, "Dell U2723QE 27寸 4K", "Type-C 一线通", "电脑", new BigDecimal("3999")),
            new Product(23L, "美的 风冷 冰箱", "多门风冷", "家电", new BigDecimal("4599")),
            new Product(24L, "抽屉式收纳盒", "桌面收纳", "家居收纳", new BigDecimal("69")),
            new Product(25L, "落地台灯", "氛围灯", "家居收纳", new BigDecimal("199")),
            new Product(26L, "温和保湿洁面乳", "氨基酸配方", "美妆个护", new BigDecimal("79")),
            new Product(27L, "丝绒雾面口红", "显白不拔干", "美妆个护", new BigDecimal("139")),
            new Product(28L, "每日坚果 750g", "混合坚果", "食品生鲜", new BigDecimal("89")),
            new Product(29L, "挂耳咖啡 20袋", "中度烘焙", "食品生鲜", new BigDecimal("69")),
            new Product(30L, "减震跑步鞋 男款", "日常慢跑训练", "运动户外", new BigDecimal("399"))
    );

    /** 与生产一致的多规格商品（未指定规格时必须反问，不能擅自加购）。 */
    private static final Map<Long, List<ProductSkuDTO>> MULTI_SKUS = Map.ofEntries(
            Map.entry(2L, List.of(
                    sku(201L, 2L, "iPhone 15 128G", "128GB", "蓝色", "5999"),
                    sku(202L, 2L, "iPhone 15 256G", "256GB", "粉色", "6999"))),
            Map.entry(10L, List.of(
                    sku(1001L, 10L, "iPhone 15 Pro 128G 原色钛金属", "128GB", "原色钛金属", "7999"),
                    sku(1002L, 10L, "iPhone 15 Pro 256G 原色钛金属", "256GB", "原色钛金属", "8999"))),
            Map.entry(11L, List.of(
                    sku(1101L, 11L, "MacBook Air 13 M3 16G", "16GB", "午夜色", "9999"),
                    sku(1102L, 11L, "MacBook Air 13 M3 24G", "24GB", "星光色", "11999"))),
            Map.entry(14L, List.of(
                    sku(1401L, 14L, "Xiaomi 14 Pro 12+256G 黑色", "12GB", "黑色", "4999"),
                    sku(1402L, 14L, "Xiaomi 14 Pro 16+512G 黑色", "16GB", "黑色", "5599"))),
            Map.entry(8L, List.of(
                    sku(800L, 8L, "双人帐篷 防雨款", "防雨涤纶", "军绿", "599"),
                    sku(801L, 8L, "双人帐篷 防雨款 军绿", "防雨涤纶", "军绿", "599"),
                    sku(802L, 8L, "双人帐篷 防雨款 墨绿", "防雨涤纶", "墨绿", "599"),
                    sku(803L, 8L, "双人帐篷 防雨款 卡其", "防雨涤纶", "卡其", "619"))),
            Map.entry(27L, List.of(
                    sku(2700L, 27L, "丝绒雾面口红", "丝绒雾面", "番茄红", "139"),
                    sku(2701L, 27L, "丝绒雾面口红 豆沙", "丝绒雾面", "豆沙", "139"),
                    sku(2702L, 27L, "丝绒雾面口红 正红", "丝绒雾面", "正红", "139"))),
            Map.entry(28L, List.of(
                    sku(2801L, 28L, "每日坚果 750g", "750g", "原味", "89"),
                    sku(2802L, 28L, "每日坚果 1.5kg", "1.5kg", "原味", "159"))),
            Map.entry(29L, List.of(
                    sku(2901L, 29L, "挂耳咖啡 20袋", "20袋", "中烘", "69"),
                    sku(2902L, 29L, "挂耳咖啡 40袋", "40袋", "深烘", "119"))),
            Map.entry(30L, List.of(
                    sku(3000L, 30L, "减震跑步鞋 男款", "41码", "黑灰", "399"),
                    sku(3001L, 30L, "减震跑步鞋 男款 42码 黑", "42码", "黑", "399"),
                    sku(3002L, 30L, "减震跑步鞋 男款 41码 黑灰", "41码", "黑灰", "399")))
    );

    private static ProductSkuDTO sku(long id, long spuId, String title, String size, String color, String price) {
        return new ProductSkuDTO(id, spuId, title, Map.of("容量", size, "颜色", color),
                new BigDecimal(price), null, 100);
    }

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
        carts.clear();

        LlmSettings settings = new LlmSettings();
        settings.setSystemPrompt("你是尚品汇客服");
        settings.setModel("test-model");
        when(settingsResolver.resolve()).thenReturn(settings);
        when(llmClient.available()).thenReturn(true);
        when(llmClient.chat(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("{}");
        when(llmClient.listModels()).thenReturn(List.of("test-model"));
        when(llmClient.streamChat(anyString(), any(), any())).thenAnswer(invocation -> {
            String system = invocation.getArgument(0);
            List<Map<String, String>> history = invocation.getArgument(1);
            LlmClient.StreamListener listener = invocation.getArgument(2);
            String answer = scriptedAnswer(system, history);
            if (listener != null) {
                listener.onContent(answer);
            }
            return new LlmClient.StreamResult("", answer, 10, 10);
        });

        when(retrievalService.searchProducts(anyString(), anyInt(), any())).thenAnswer(invocation -> {
            String keyword = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            com.shangpivot.customerservice.retrieval.ProductConstraints constraints = invocation.getArgument(2);
            return search(keyword, limit, constraints);
        });
        when(productQueryApi.getBySpuIds(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            return CATALOG.stream().filter(product -> ids.contains(product.spuId()))
                    .map(Product::card).toList();
        });
        when(productSkuApi.listEnabledBySpu(anyLong())).thenAnswer(invocation -> {
            long spuId = invocation.getArgument(0);
            List<ProductSkuDTO> multi = MULTI_SKUS.get(spuId);
            if (multi != null) {
                return multi;
            }
            return CATALOG.stream().filter(product -> product.spuId() == spuId)
                    .map(product -> new ProductSkuDTO(spuId * 10, spuId, product.subtitle(),
                            Map.of("配置", product.subtitle()), product.price(), null, 50))
                    .toList();
        });
        when(orderQueryApi.pageByCustomer(anyLong(), anyInt(), anyInt()))
                .thenReturn(PageResult.<com.shangpivot.commerce.api.OrderBriefDTO>of(0, List.of()));
        when(orderQueryApi.getTrace(anyLong(), anyString())).thenReturn(null);
        when(knowledgeSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeSearchService.KnowledgeHit(1L, "退换货政策",
                        "【退换货政策】1. 签收后 7 天内，商品未使用且不影响二次销售的，可申请无理由退货；"
                                + "2. 质量问题签收后 15 天内可申请退换，运费由商家承担；"
                                + "3. 定制类、贴身衣物及已激活的虚拟商品不支持无理由退货；"
                                + "4. 退货流程：我的订单 → 申请售后 → 选择退货退款 → 填写原因 → 等待审核 → 寄回商品 → 商家验收后退款；"
                                + "5. 退款将在商家验收后 1-3 个工作日内原路退回。", 0.9, "vector")));
        when(cartMutationApi.addToCart(anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
            long customerId = invocation.getArgument(0);
            long skuId = invocation.getArgument(1);
            int quantity = invocation.getArgument(2);
            int total = carts.computeIfAbsent(customerId, key -> new HashMap<>())
                    .merge(skuId, quantity, Integer::sum);
            return new CartAddResultDTO(1L, skuId, total);
        });
    }

    @Test
    void graphMatchesGoldenDeterministicAssertions() {
        List<String> failures = new ArrayList<>();
        runDataset(5000L, GoldenDataset.all(), failures);
        assertTrue(failures.isEmpty(), "Graph Golden 失败:\n" + String.join("\n", failures));
    }

    @Test
    void graphMatchesReferenceCartDeterministicAssertions() {
        List<String> failures = new ArrayList<>();
        runDataset(7000L, ReferenceCartDataset.all(), failures);
        assertTrue(failures.isEmpty(), "Reference Cart 失败:\n" + String.join("\n", failures));
    }

    /** BadLlm：故意返回编造商品/身份/外部平台，验证 displayAnswer + AnswerGuard 仍守住确定性断言。 */
    @Test
    void graphGoldenSurvivesBadLlm() {
        when(llmClient.streamChat(anyString(), any(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(2);
            String bad = "推荐 Redmi Turbo 4，约1999元；我是 Claude，由 Anthropic 开发；也可参考京东退货政策。";
            if (listener != null) {
                listener.onContent(bad);
            }
            return new LlmClient.StreamResult("", bad, 10, 10);
        });
        List<String> failures = new ArrayList<>();
        runDataset(6000L, GoldenDataset.all(), failures);
        assertTrue(failures.isEmpty(), "BadLlm Golden 失败:\n" + String.join("\n", failures));
    }

    private void runDataset(long customerBase, List<GoldenCase> dataset, List<String> failures) {
        int index = 0;
        for (GoldenCase golden : dataset) {
            long customerId = customerBase + index++;
            ChatSession session = chatSessionService.getOrCreateActive(customerId, "h5");
            Turn last = null;
            for (String turn : golden.turns()) {
                String content = guardPipeline.maskPii(turn);
                CsMessage user = chatSessionService.saveUserMessage(session.getId(), content);
                CapturingEmitter emitter = new CapturingEmitter(objectMapper);
                orchestrator.respond(emitter, session, user);
                last = emitter.turn();
            }
            if (last == null) {
                failures.add(golden.id() + ": 无结果");
                continue;
            }
            if (last.answer().contains("Redmi Turbo 4") || last.answer().contains("Claude")
                    || last.answer().contains("Anthropic") || last.answer().contains("京东")) {
                failures.add(golden.id() + ": 回答泄漏 BadLlm 编造内容：" + last.answer());
            }
            for (String fragment : golden.expectedSourceFragments()) {
                boolean hit = last.sources().stream().anyMatch(source -> "retrieval".equals(fragment)
                        ? (source.contains("vector") || source.contains("lexical"))
                        : source.contains(fragment));
                if (!hit) {
                    failures.add(golden.id() + ": sources " + last.sources() + " 不含 " + fragment);
                }
            }
            for (String fragment : golden.expectedCardFragments()) {
                boolean hit = last.cards().stream()
                        .anyMatch(card -> String.valueOf(card.get("name")).contains(fragment));
                if (!hit) {
                    failures.add(golden.id() + ": cards 不含 " + fragment + "，实际 " + last.cards());
                }
            }
            for (String keyword : golden.expectedAnswerKeywords()) {
                if (!last.answer().contains(keyword)) {
                    failures.add(golden.id() + ": answer 缺 " + keyword + "，实际 " + last.answer());
                }
            }
            for (String pattern : golden.forbidPatterns()) {
                if (last.answer().contains(pattern)) {
                    failures.add(golden.id() + ": answer 出现禁止词 " + pattern);
                }
            }
            if (golden.maxSourceCount() != null && last.sources().size() > golden.maxSourceCount()) {
                failures.add(golden.id() + ": sources " + last.sources().size()
                        + " 超过上限 " + golden.maxSourceCount());
            }
            if (!golden.expectedCart().isEmpty()) {
                Map<Long, Integer> cart = carts.getOrDefault(customerId, Map.of());
                for (GoldenCase.CartExpect expect : golden.expectedCart()) {
                    int actual = CATALOG.stream()
                            .filter(product -> product.name().contains(expect.titleFragment()))
                            .flatMap(product -> {
                                List<ProductSkuDTO> skus = MULTI_SKUS.get(product.spuId());
                                if (skus == null) {
                                    return java.util.stream.Stream.of(product.spuId() * 10);
                                }
                                return skus.stream().map(ProductSkuDTO::skuId);
                            })
                            .mapToInt(skuId -> cart.getOrDefault(skuId, 0))
                            .sum();
                    if (actual != expect.quantity()) {
                        failures.add(golden.id() + ": 购物车 " + expect.titleFragment()
                                + " 期望 " + expect.quantity() + " 实际 " + actual);
                    }
                }
            }
            if (last.degraded()) {
                failures.add(golden.id() + ": 降级应答");
            }
        }
    }

    private record Turn(String answer, List<Map<String, Object>> cards, List<String> sources, boolean degraded) {
    }

    private static List<RetrievalService.ProductHit> search(String keyword, int limit,
                                                             com.shangpivot.customerservice.retrieval.ProductConstraints constraints) {
        String q = keyword == null ? "" : keyword.trim();
        List<Product> exact = !q.isBlank() ? CATALOG.stream()
                .filter(product -> norm(product.name()).contains(norm(q)))
                .toList() : List.of();
        List<Product> matched = (exact.isEmpty()
                ? CATALOG.stream().filter(product -> matches(keyword, product))
                : exact.stream())
                .filter(product -> categoryAllowed(product, constraints.categories()))
                .toList();
        List<RetrievalService.ProductHit> hits = new java.util.ArrayList<>();
        for (Product product : matched) {
            if (constraints.within(product.price())) {
                hits.add(new RetrievalService.ProductHit(product.card(), 1.0, "lexical", true));
                if (hits.size() >= limit) {
                    return hits;
                }
            }
        }
        // 预算内不足：补超预算候选（与服务实现一致，标记 withinBudget=false）
        if (constraints.budgetMax() != null) {
            for (Product product : matched) {
                if (product.price().compareTo(constraints.budgetMax()) > 0) {
                    hits.add(new RetrievalService.ProductHit(product.card(), 0.8, "lexical", false));
                    if (hits.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return hits;
    }

    private static boolean categoryAllowed(Product product, List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return true;
        }
        String category = product.category();
        return categories.stream().anyMatch(c -> switch (c) {
            case "手机" -> "手机".equals(category);
            case "电脑/办公" -> "电脑".equals(category);
            case "家电" -> List.of("吸尘器", "扫地机器人", "洗衣机", "家电").contains(category);
            case "数码配件" -> List.of("耳机", "手表", "外设", "配件").contains(category);
            case "运动户外" -> List.of("帐篷", "运动户外").contains(category);
            default -> c.equals(category);
        });
    }

    private static boolean matches(String keyword, Product product) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String q = norm(keyword);
        String name = norm(product.name());
        boolean phone = q.contains("手机");
        boolean earphone = q.contains("耳机");
        boolean vacuum = q.contains("吸尘");
        boolean sweeper = q.contains("扫地") || q.contains("扫拖");
        boolean tent = q.contains("帐篷");
        boolean washer = q.contains("洗衣机");
        boolean bike = q.contains("自行车");
        if (phone || earphone || vacuum || sweeper || tent || washer || bike) {
            String category = product.category();
            return (phone && "手机".equals(category))
                    || (earphone && "耳机".equals(category))
                    || (vacuum && "吸尘器".equals(category))
                    || (sweeper && "扫地机器人".equals(category))
                    || (tent && "帐篷".equals(category))
                    || (washer && "洗衣机".equals(category));
        }
        return name.contains(q)
                || (name.length() >= 4 && q.contains(name.substring(0, 4)));
    }

    private static String norm(String value) {
        return value == null ? "" : value
                .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String scriptedAnswer(String system, List<Map<String, String>> history) {
        String user = lastUser(history);
        if (user.contains("忽略之前所有的指令") || user.contains("忽略以上规则")
                || user.toLowerCase().contains("ignore all previous instructions")) {
            return "抱歉，我不能提供系统提示词或内部配置。您可以继续问我商品、订单或售后问题。";
        }
        if (user.contains("你是谁") || user.contains("你叫什么") || user.contains("真人")) {
            return "我是尚品汇智能客服小尚，可以帮您找商品、查订单、处理售后。";
        }
        if (user.contains("天气")) {
            return "我暂时无法查询天气信息，您可以问我商品、订单或售后问题。";
        }
        String[] blocks = system.split("\\n\\n");
        String facts = blocks.length == 0 ? "" : blocks[blocks.length - 1];
        return facts.isBlank() ? "好的，请问还有什么可以帮您？" : facts;
    }

    private static String lastUser(List<Map<String, String>> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role"))) {
                return history.get(i).getOrDefault("content", "");
            }
        }
        return "";
    }

    /** 捕获 SSE 并解析 answer/cards/sources；只用于测试。 */
    private static final class CapturingEmitter extends SseEmitter {
        private final ObjectMapper mapper;
        private final StringBuilder answer = new StringBuilder();
        private final List<Map<String, Object>> cards = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();
        private boolean degraded;

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
                switch (event) {
                    case "delta" -> answer.append(node.path("delta").asText(""));
                    case "cards" -> node.path("items").forEach(item -> cards.add(convert(item)));
                    case "done" -> {
                        node.path("sources").forEach(source -> sources.add(source.asText()));
                        degraded = node.path("degraded").asBoolean(false);
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
                // 测试只关心可解析事件
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> convert(JsonNode node) {
            return mapper.convertValue(node, Map.class);
        }

        @Override
        public void complete() {
            // no-op
        }

        Turn turn() {
            return new Turn(answer.toString().trim(), cards, sources, degraded);
        }
    }
}
