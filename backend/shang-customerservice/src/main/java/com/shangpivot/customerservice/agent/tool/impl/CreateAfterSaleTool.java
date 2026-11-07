package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.AfterSaleApi;
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

/** 发起售后（高风险写，必须确认）。 */
@Component
public class CreateAfterSaleTool extends AbstractTool {

    private static final Set<String> TYPES = Set.of("REFUND", "RETURN_REFUND", "EXCHANGE");

    private final AfterSaleApi afterSaleApi;

    public CreateAfterSaleTool(AfterSaleApi afterSaleApi) {
        super("createAfterSale", "为当前顾客的订单发起退款/退货退款/换货申请",
                ToolRisk.WRITE_HIGH, Set.of(AgentName.AFTER_SALE), Duration.ofSeconds(3), true,
                schema(List.of("orderNo", "type", "reason"), Map.of(
                        "orderNo", property("string", "订单号"),
                        "type", property("string", "REFUND / RETURN_REFUND / EXCHANGE"),
                        "reason", property("string", "售后原因，≤512 字"))));
        this.afterSaleApi = afterSaleApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String orderNo = stringArg(args, "orderNo");
        String type = stringArg(args, "type");
        String reason = stringArg(args, "reason");
        if (orderNo == null || orderNo.isBlank() || type == null || !TYPES.contains(type)
                || reason == null || reason.isBlank()) {
            return ToolResult.fail("INVALID_ARGS", "订单号/类型/原因无效");
        }
        Long id = afterSaleApi.apply(context.customerId(), orderNo.trim(), type, reason.trim());
        return ToolResult.ok(Map.of("afterSaleId", id, "orderNo", orderNo.trim(), "type", type),
                "售后申请已提交，售后单号 " + id);
    }
}
