package com.shangpivot.commerce.controller;

import com.shangpivot.commerce.application.AfterSaleService;
import com.shangpivot.commerce.entity.AfterSale;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 售后单查询与审核 API。
 * <p>路径前缀 /admin/after-sales。
 */
@RestController
@RequestMapping("/admin/after-sales")
@RequiredArgsConstructor
@Tag(name = "售后管理", description = "售后单的查询、详情与审核")
public class AfterSaleController {
    private final AfterSaleService afterSaleService;

    @GetMapping
    @Operation(summary = "分页查询售后单", description = "按状态、类型、售后单号筛选售后列表")
    public Result<PageResult<AfterSale>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "售后状态：PENDING_AUDIT / REFUNDING / COMPLETED / REJECTED", example = "PENDING_AUDIT") @RequestParam(required = false) String status,
            @Parameter(description = "售后类型：REFUND / RETURN", example = "REFUND") @RequestParam(required = false) String type,
            @Parameter(description = "售后单号（模糊匹配）", example = "AS20250913001") @RequestParam(required = false) String afterSaleNo) {
        return Result.ok(afterSaleService.page(page, size, status, type, afterSaleNo));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取售后详情", description = "返回售后单信息及关联订单")
    public Result<Map<String, Object>> detail(
            @Parameter(description = "售后单ID", example = "1") @PathVariable Long id) {
        return Result.ok(afterSaleService.detail(id));
    }

    @PostMapping("/{id}/audit")
    @OpLog(module = "aftersale", action = "audit")
    @Operation(summary = "审核售后单", description = "对待审核售后单进行通过或驳回")
    public Result<Void> audit(
            @Parameter(description = "售后单ID", example = "1") @PathVariable Long id,
            @RequestBody AfterSaleService.AuditReq req) {
        afterSaleService.audit(id, req);
        return Result.ok();
    }
}
