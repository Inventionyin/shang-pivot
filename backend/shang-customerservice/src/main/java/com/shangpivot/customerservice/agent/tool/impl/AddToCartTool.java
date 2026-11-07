package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.CartAddResultDTO;
import com.shangpivot.commerce.api.CartMutationApi;
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

/** 加购（低风险可逆写，默认直接执行；幂等键防重复）。 */
@Component
public class AddToCartTool extends AbstractTool {

    private final CartMutationApi cartMutationApi;

    public AddToCartTool(CartMutationApi cartMutationApi) {
        super("addToCart", "把指定 SKU 加入当前顾客购物车",
                ToolRisk.WRITE_LOW, Set.of(AgentName.SHOPPING_GUIDE), Duration.ofSeconds(3), true,
                schema(List.of("skuId", "quantity"), Map.of(
                        "skuId", property("integer", "SKU ID"),
                        "quantity", property("integer", "数量，1-99"))));
        this.cartMutationApi = cartMutationApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        Long skuId = longArg(args, "skuId");
        int quantity = intArg(args, "quantity", 1, 1, 99);
        if (skuId == null) {
            return ToolResult.fail("INVALID_ARGS", "skuId 无效");
        }
        CartAddResultDTO added = cartMutationApi.addToCart(context.customerId(), skuId, quantity);
        return ToolResult.ok(added, "已加入购物车，当前数量 " + added.quantity());
    }
}
