package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.OrderCommandApi;
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

/** 确认收货（高风险写，必须确认）。 */
@Component
public class ConfirmReceiveTool extends AbstractTool {

    private final OrderCommandApi orderCommandApi;

    public ConfirmReceiveTool(OrderCommandApi orderCommandApi) {
        super("confirmReceive", "确认收货；仅 PENDING_RECEIVE 可操作",
                ToolRisk.WRITE_HIGH, Set.of(AgentName.ORDER), Duration.ofSeconds(3), true,
                schema(List.of("orderNo"), Map.of(
                        "orderNo", property("string", "订单号"))));
        this.orderCommandApi = orderCommandApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String orderNo = stringArg(args, "orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            return ToolResult.fail("INVALID_ARGS", "订单号必填");
        }
        orderCommandApi.confirmReceive(context.customerId(), orderNo.trim());
        return ToolResult.ok(Map.of("orderNo", orderNo.trim(), "status", "COMPLETED"),
                "订单 " + orderNo + " 已确认收货");
    }
}
