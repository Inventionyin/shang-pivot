package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import com.shangpivot.customerservice.retrieval.ProductConstraints;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品检索：复用 V3.0 的向量/词法双检索，保证 graph 与 legacy 的口径一致。 */
@Component
public class ProductSearchTool extends AbstractTool {

    private final RetrievalService retrievalService;

    public ProductSearchTool(RetrievalService retrievalService) {
        super("searchProducts", "按关键词检索在售商品卡片（向量优先，失败回落词法）",
                ToolRisk.READ, Set.of(AgentName.SHOPPING_GUIDE), Duration.ofSeconds(3), false,
                schema(List.of("keyword"), Map.of(
                        "keyword", property("string", "搜索关键词，如 手机、跑步鞋、耳机"),
                        "limit", property("integer", "返回数量，默认 3，最大 5"),
                        "category", property("string", "品类精确值，如 手机、电脑/办公、家电"),
                        "categories", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "多品类精确值（与 category 二选一）"),
                        "priceMin", property("number", "预算下限（元），可空"),
                        "priceMax", property("number", "预算上限（元），可空"))));
        this.retrievalService = retrievalService;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String keyword = stringArg(args, "keyword");
        int limit = intArg(args, "limit", 3, 1, 5);
        List<String> categories = new ArrayList<>();
        Object rawCategories = args == null ? null : args.get("categories");
        if (rawCategories instanceof java.util.Collection<?> values) {
            values.stream().map(String::valueOf).filter(s -> !s.isBlank()).forEach(categories::add);
        }
        String category = stringArg(args, "category");
        if (categories.isEmpty() && category != null && !category.isBlank()) {
            categories.add(category);
        }
        ProductConstraints constraints = new ProductConstraints(
                categories, decimalArg(args, "priceMin"), decimalArg(args, "priceMax"), "",
                !categories.isEmpty());
        List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(keyword, limit, constraints);
        return ToolResult.ok(hits, "检索到 " + hits.size() + " 个在售商品");
    }

    private static BigDecimal decimalArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
