package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
                        "limit", property("integer", "返回数量，默认 3，最大 5"))));
        this.retrievalService = retrievalService;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String keyword = stringArg(args, "keyword");
        int limit = intArg(args, "limit", 3, 1, 5);
        List<RetrievalService.ProductHit> hits = retrievalService.searchProducts(keyword, limit);
        return ToolResult.ok(hits, "检索到 " + hits.size() + " 个在售商品");
    }
}
