package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
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

/** 规格/库存查询：返回真实 SKU 与可售库存。 */
@Component
public class StockTool extends AbstractTool {

    private final ProductSkuApi productSkuApi;

    public StockTool(ProductSkuApi productSkuApi) {
        super("getStock", "查询商品全部启用规格、价格与实时可售库存",
                ToolRisk.READ, Set.of(AgentName.SHOPPING_GUIDE), Duration.ofSeconds(1), false,
                schema(List.of("spuId"), Map.of(
                        "spuId", property("integer", "商品 SPU ID"))));
        this.productSkuApi = productSkuApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        Long spuId = longArg(args, "spuId");
        if (spuId == null) {
            return ToolResult.fail("INVALID_ARGS", "spuId 无效");
        }
        List<ProductSkuDTO> skus = productSkuApi.listEnabledBySpu(spuId);
        return ToolResult.ok(skus, skus.isEmpty() ? "暂无可售规格" : "共 " + skus.size() + " 个可售规格");
    }
}
