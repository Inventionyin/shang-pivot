package com.shangpivot.customerservice.controller;

import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import com.shangpivot.customerservice.entity.CsTicket;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.customerservice.service.QuickReplyAdminService;
import com.shangpivot.customerservice.service.SeatAdminService;
import com.shangpivot.customerservice.service.StatsService;
import com.shangpivot.customerservice.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 工单 / 快捷回复 / 坐席 / 看板（/admin/customer-service/**）。 */
@Tag(name = "管理端-智能客服运营")
@RestController
@RequestMapping("/admin/customer-service")
@RequiredArgsConstructor
public class CsOpsController {

    private final TicketService ticketService;
    private final QuickReplyAdminService quickReplyAdminService;
    private final SeatAdminService seatAdminService;
    private final StatsService statsService;

    // ---------- 工单 ----------

    public record TicketRequest(Long sessionId, Long customerId, String type, String title, String remark) {
    }

    public record TransitionRequest(String target) {
    }

    @Operation(summary = "工单分页")
    @GetMapping("/tickets")
    public Result<?> tickets(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String type,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int size) {
        return Result.ok(ticketService.page(status, type, page, Math.min(size, 50)));
    }

    @Operation(summary = "新建工单")
    @PostMapping("/tickets")
    @OpLog(module = "cs-ticket", action = "create")
    public Result<CsTicket> createTicket(@RequestBody TicketRequest req) {
        return Result.ok(ticketService.create(req.sessionId(), req.customerId(), req.type(),
                req.title(), req.remark(), null));
    }

    @Operation(summary = "更新工单")
    @PutMapping("/tickets/{id}")
    @OpLog(module = "cs-ticket", action = "update")
    public Result<CsTicket> updateTicket(@PathVariable Long id, @RequestBody TicketRequest req) {
        return Result.ok(ticketService.update(id, req.type(), req.title(), req.remark()));
    }

    @Operation(summary = "工单状态流转")
    @PostMapping("/tickets/{id}/transition")
    @OpLog(module = "cs-ticket", action = "transition")
    public Result<CsTicket> transitionTicket(@PathVariable Long id, @RequestBody TransitionRequest req) {
        return Result.ok(ticketService.transition(id, req.target(), SecurityContexts.requireUserId()));
    }

    @Operation(summary = "删除工单")
    @DeleteMapping("/tickets/{id}")
    @OpLog(module = "cs-ticket", action = "delete")
    public Result<Void> deleteTicket(@PathVariable Long id) {
        ticketService.delete(id);
        return Result.ok();
    }

    // ---------- 快捷回复 ----------

    public record GroupRequest(String name, Integer sort) {
    }

    public record ItemRequest(Long groupId, String title, String content, Integer sort, Boolean isRecommended) {
    }

    @Operation(summary = "快捷回复树（分组+话术）")
    @GetMapping("/quick-replies")
    public Result<Map<String, Object>> quickReplies() {
        return Result.ok(quickReplyAdminService.tree());
    }

    @Operation(summary = "新建分组")
    @PostMapping("/quick-reply-groups")
    @OpLog(module = "cs-quick", action = "create-group")
    public Result<Long> createGroup(@RequestBody GroupRequest req) {
        return Result.ok(quickReplyAdminService.createGroup(req.name(), req.sort() == null ? 0 : req.sort()));
    }

    @Operation(summary = "删除分组")
    @DeleteMapping("/quick-reply-groups/{id}")
    @OpLog(module = "cs-quick", action = "delete-group")
    public Result<Void> deleteGroup(@PathVariable Long id) {
        quickReplyAdminService.deleteGroup(id);
        return Result.ok();
    }

    @Operation(summary = "新建话术")
    @PostMapping("/quick-replies")
    @OpLog(module = "cs-quick", action = "create")
    public Result<Long> createItem(@RequestBody ItemRequest req) {
        return Result.ok(quickReplyAdminService.createItem(req.groupId(), req.title(), req.content(),
                req.sort() == null ? 0 : req.sort(), Boolean.TRUE.equals(req.isRecommended())));
    }

    @Operation(summary = "更新话术")
    @PutMapping("/quick-replies/{id}")
    @OpLog(module = "cs-quick", action = "update")
    public Result<Void> updateItem(@PathVariable Long id, @RequestBody ItemRequest req) {
        quickReplyAdminService.updateItem(id, req.groupId(), req.title(), req.content(),
                req.sort() == null ? 0 : req.sort(), Boolean.TRUE.equals(req.isRecommended()));
        return Result.ok();
    }

    @Operation(summary = "删除话术")
    @DeleteMapping("/quick-replies/{id}")
    @OpLog(module = "cs-quick", action = "delete")
    public Result<Void> deleteItem(@PathVariable Long id) {
        quickReplyAdminService.deleteItem(id);
        return Result.ok();
    }

    // ---------- 坐席 ----------

    public record SeatStatusRequest(String status) {
    }

    public record SeatConcurrencyRequest(Integer maxConcurrent) {
    }

    @Operation(summary = "坐席列表（含当前并发）")
    @GetMapping("/seats")
    public Result<?> seats() {
        return Result.ok(seatAdminService.list());
    }

    @Operation(summary = "更新坐席状态")
    @PutMapping("/seats/{id}/status")
    @OpLog(module = "cs-seat", action = "status")
    public Result<?> seatStatus(@PathVariable Long id, @RequestBody SeatStatusRequest req) {
        return Result.ok(seatAdminService.updateStatus(id, req.status()));
    }

    @Operation(summary = "更新坐席最大并发")
    @PutMapping("/seats/{id}/max-concurrent")
    @OpLog(module = "cs-seat", action = "max-concurrent")
    public Result<?> seatConcurrency(@PathVariable Long id, @RequestBody SeatConcurrencyRequest req) {
        return Result.ok(seatAdminService.updateMaxConcurrent(id, req.maxConcurrent()));
    }

    // ---------- 看板 ----------

    @Operation(summary = "服务看板", description = "range=today|week")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "today") String range) {
        return Result.ok(statsService.dashboard(range));
    }

    @Operation(summary = "指代加购健康", description = "days=1..90；失败率/加购成功率/反问率/检索命中率")
    @GetMapping("/reference-health")
    public Result<Map<String, Object>> referenceHealth(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.referenceHealth(days));
    }

    @Operation(summary = "指代加购健康趋势", description = "按天返回运行/加购/反问/检索指标")
    @GetMapping("/reference-health/trend")
    public Result<List<Map<String, Object>>> referenceTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.referenceTrend(days));
    }

    // ---------- 答案质量 ----------

    @Operation(summary = "答案质量看板", description = "days=1..90；点赞率/点踩率/点踩原因")
    @GetMapping("/quality")
    public Result<Map<String, Object>> quality(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.answerQuality(days));
    }

    @Operation(summary = "答案质量趋势", description = "按天返回点赞/点踩/点踩率")
    @GetMapping("/quality/trend")
    public Result<List<Map<String, Object>>> qualityTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.answerQualityTrend(days));
    }

    @Operation(summary = "最近点踩明细")
    @GetMapping("/quality/negative-feedback")
    public Result<List<Map<String, Object>>> negativeFeedback(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(statsService.recentNegativeFeedback(limit));
    }
}
