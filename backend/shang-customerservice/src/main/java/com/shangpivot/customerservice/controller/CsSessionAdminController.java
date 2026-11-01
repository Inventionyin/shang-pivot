package com.shangpivot.customerservice.controller;

import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.service.CsAgentAdminService;
import com.shangpivot.customerservice.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 坐席会话管理（/admin/customer-service/sessions）。 */
@Tag(name = "管理端-智能客服会话")
@Validated
@RestController
@RequestMapping("/admin/customer-service/sessions")
@RequiredArgsConstructor
public class CsSessionAdminController {

    private final CsAgentAdminService agentAdminService;
    private final TicketService ticketService;

    public record ReplyRequest(@NotBlank @Size(max = 500) String content) {
    }

    public record TransferRequest(Long targetSeatId) {
    }

    public record EndRequest(String summary, Boolean createTicket, String ticketType, String ticketTitle) {
    }

    @Operation(summary = "会话分页")
    @GetMapping
    public Result<?> page(@RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size) {
        return Result.ok(agentAdminService.page(status, page, Math.min(size, 50)));
    }

    @Operation(summary = "会话详情（含消息）")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(agentAdminService.detail(id));
    }

    @Operation(summary = "接入排队会话")
    @PostMapping("/{id}/claim")
    public Result<?> claim(@PathVariable Long id) {
        return Result.ok(agentAdminService.claim(id, SecurityContexts.requireUserId()));
    }

    @Operation(summary = "坐席回复")
    @PostMapping("/{id}/messages")
    public Result<CsMessage> reply(@PathVariable Long id, @RequestBody ReplyRequest req) {
        return Result.ok(agentAdminService.reply(id, SecurityContexts.requireUserId(), req.content().trim()));
    }

    @Operation(summary = "转接给其他坐席")
    @PostMapping("/{id}/transfer")
    public Result<?> transfer(@PathVariable Long id, @RequestBody TransferRequest req) {
        return Result.ok(agentAdminService.transfer(id, SecurityContexts.requireUserId(), req.targetSeatId()));
    }

    @Operation(summary = "结束会话（可生成工单）")
    @PostMapping("/{id}/end")
    public Result<?> end(@PathVariable Long id, @RequestBody(required = false) EndRequest req) {
        String summary = req == null ? null : req.summary();
        var session = agentAdminService.end(id, summary);
        if (req != null && Boolean.TRUE.equals(req.createTicket()) && req.ticketTitle() != null) {
            ticketService.create(id, session.getCustomerId(),
                    req.ticketType() == null ? "consult" : req.ticketType(),
                    req.ticketTitle(), summary, session.getSeatId());
        }
        return Result.ok(Map.of("sessionId", session.getId(), "status", session.getStatus()));
    }

    @Operation(summary = "退回智能客服")
    @PostMapping("/{id}/back-to-ai")
    public Result<?> backToAi(@PathVariable Long id) {
        return Result.ok(agentAdminService.backToAi(id));
    }
}
