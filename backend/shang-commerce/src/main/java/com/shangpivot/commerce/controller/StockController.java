package com.shangpivot.commerce.controller;

import com.shangpivot.commerce.application.StockService;
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
 * 库存查询与手工调整 API。
 * <p>路径前缀 /admin/stocks。
 */
@RestController
@RequestMapping("/admin/stocks")
@RequiredArgsConstructor
@Tag(name = "库存管理", description = "SKU 库存查询与手动调整")
public class StockController {
    private final StockService stockService;

    @GetMapping
    @Operation(summary = "分页查询库存",
            description = "按 SKU、仓库筛选；lowOnly=仅低库存（可售≤阈值），warnOnly=预警档（可售≤阈值×1.5，含关注）")
    public Result<PageResult<Map<String, Object>>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "SKU ID", example = "1") @RequestParam(required = false) Long skuId,
            @Parameter(description = "仓库ID", example = "1") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "仅低库存（可售 ≤ 阈值）", example = "false") @RequestParam(required = false) Boolean lowOnly,
            @Parameter(description = "预警档：低库存 + 关注（可售 ≤ 阈值 × 1.5）", example = "false") @RequestParam(required = false) Boolean warnOnly) {
        return Result.ok(stockService.page(page, size, skuId, warehouseId, lowOnly, warnOnly));
    }

    @PutMapping("/adjust")
    @OpLog(module = "stock", action = "adjust")
    @Operation(summary = "调整库存", description = "手动增减指定 SKU 在指定仓库的物理库存")
    public Result<Void> adjust(@RequestBody StockService.AdjustReq req) {
        stockService.adjust(req);
        return Result.ok();
    }
}
