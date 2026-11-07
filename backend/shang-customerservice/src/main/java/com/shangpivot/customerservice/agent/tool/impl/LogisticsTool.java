package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
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

/** 物流轨迹查询。 */
@Component
public class LogisticsTool extends AbstractTool {

    private final OrderQueryApi orderQueryApi;

    public LogisticsTool(OrderQueryApi orderQueryApi) {
        super("getLogistics", "查询指定订单的承运商、运单号与物流轨迹",
                ToolRisk.READ, Set.of(AgentName.ORDER), Duration.ofSeconds(1), false,
                schema(List.of("orderNo"), Map.of(
                        "orderNo", property("string", "订单号"))));
        this.orderQueryApi = orderQueryApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String orderNo = stringArg(args, "orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            return ToolResult.fail("INVALID_ARGS", "订单号必填");
        }
        OrderTraceDTO trace = orderQueryApi.getTrace(context.customerId(), orderNo.trim());
        if (trace == null) {
            return ToolResult.fail("NOT_FOUND", "未找到订单 " + orderNo);
        }
        if (trace.trackingNo() == null) {
            return ToolResult.ok(trace, "订单 " + trace.orderNo() + " 暂无物流记录");
        }
        return ToolResult.ok(trace, "承运商 " + trace.carrier() + "，运单 " + trace.trackingNo());
    }
}
