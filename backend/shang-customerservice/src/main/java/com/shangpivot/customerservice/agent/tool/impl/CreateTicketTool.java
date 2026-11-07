package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import com.shangpivot.customerservice.entity.CsTicket;
import com.shangpivot.customerservice.service.TicketService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 建客服工单（低风险写，用于售后/转人工留痕）。 */
@Component
public class CreateTicketTool extends AbstractTool {

    private final TicketService ticketService;

    public CreateTicketTool(TicketService ticketService) {
        super("createTicket", "为当前会话创建客服工单",
                ToolRisk.WRITE_LOW, Set.of(AgentName.AFTER_SALE, AgentName.HANDOFF), Duration.ofSeconds(3), false,
                schema(List.of("title"), Map.of(
                        "type", property("string", "工单类型，默认 AFTER_SALE"),
                        "title", property("string", "工单标题"),
                        "remark", property("string", "补充说明"))));
        this.ticketService = ticketService;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String title = stringArg(args, "title");
        if (title == null || title.isBlank()) {
            return ToolResult.fail("INVALID_ARGS", "工单标题必填");
        }
        String type = stringArg(args, "type");
        if (type == null || type.isBlank()) {
            type = "AFTER_SALE";
        }
        CsTicket ticket = ticketService.create(context.sessionId(), context.customerId(), type,
                title.trim(), stringArg(args, "remark"), null);
        return ToolResult.ok(Map.of("ticketId", ticket.getId(), "title", ticket.getTitle()),
                "工单已创建：" + ticket.getTitle());
    }
}
