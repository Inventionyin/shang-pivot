package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.commerce.api.OrderBriefDTO;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 订单查询：有订单号查单笔轨迹，无订单号查最近 3 笔。 */
@Component
public class OrderQueryTool extends AbstractTool {

    private final OrderQueryApi orderQueryApi;

    public OrderQueryTool(OrderQueryApi orderQueryApi) {
        super("getOrder", "查询当前顾客的订单状态/金额；有订单号时返回物流轨迹",
                ToolRisk.READ, Set.of(AgentName.ORDER, AgentName.AFTER_SALE), Duration.ofSeconds(1), false,
                schema(java.util.List.of(), Map.of(
                        "orderNo", property("string", "订单号；不传则返回最近 3 笔订单"))));
        this.orderQueryApi = orderQueryApi;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String orderNo = stringArg(args, "orderNo");
        if (orderNo != null && !orderNo.isBlank()) {
            OrderTraceDTO trace = orderQueryApi.getTrace(context.customerId(), orderNo.trim());
            if (trace == null) {
                return ToolResult.fail("NOT_FOUND", "未找到订单 " + orderNo);
            }
            return ToolResult.ok(trace, "订单 " + trace.orderNo() + " 状态 " + trace.status());
        }
        PageResult<OrderBriefDTO> page = orderQueryApi.pageByCustomer(context.customerId(), 1, 3);
        return ToolResult.ok(page.getRecords(), "最近 " + page.getRecords().size() + " 笔订单");
    }
}
