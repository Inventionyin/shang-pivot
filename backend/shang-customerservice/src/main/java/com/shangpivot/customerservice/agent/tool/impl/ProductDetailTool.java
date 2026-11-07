package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品详情：按 SPU ID 取真实卡片。 */
@Component
public class ProductDetailTool extends AbstractTool {

    private final ProductQueryApi productQueryApi;

    public ProductDetailTool(ProductQueryApi productQueryApi) {
        super("getProduct", "按 SPU ID 取商品名称、价格、副标题、图片",
                ToolRisk.READ, Set.of(AgentName.SHOPPING_GUIDE), Duration.ofSeconds(1), false,
                schema(List.of("spuId"), Map.of(
                        "spuId", property("integer", "商品 SPU ID"))));
        this.productQueryApi = productQueryApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        Long spuId = longArg(args, "spuId");
        if (spuId == null) {
            return ToolResult.fail("INVALID_ARGS", "spuId 无效");
        }
        List<ProductCardDTO> cards = productQueryApi.getBySpuIds(List.of(spuId));
        if (cards.isEmpty()) {
            return ToolResult.fail("NOT_FOUND", "商品不存在或已下架");
        }
        return ToolResult.ok(cards.get(0), "商品：" + cards.get(0).name());
    }
}
