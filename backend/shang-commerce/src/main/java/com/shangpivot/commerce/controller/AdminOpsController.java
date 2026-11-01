package com.shangpivot.commerce.controller;

import com.shangpivot.commerce.application.HomeSlotService;
import com.shangpivot.commerce.application.InOutOrderService;
import com.shangpivot.commerce.application.ReviewAdminService;
import com.shangpivot.commerce.application.SiteBrandService;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "V2运营补丁", description = "站点品牌、运营位、评价、出入库")
public class AdminOpsController {

    private final SiteBrandService siteBrandService;
    private final HomeSlotService homeSlotService;
    private final ReviewAdminService reviewAdminService;
    private final InOutOrderService inOutOrderService;

    @GetMapping("/admin/site/brand")
    @Operation(summary = "获取站点品牌")
    public Result<Map<String, Object>> getBrand() {
        return Result.ok(siteBrandService.get());
    }

    @PutMapping("/admin/site/brand")
    @OpLog(module = "content", action = "brand")
    @Operation(summary = "更新站点品牌")
    public Result<Map<String, Object>> updateBrand(@RequestBody Map<String, String> body) {
        return Result.ok(siteBrandService.update(body));
    }

    @GetMapping("/admin/home/slots")
    @Operation(summary = "运营位列表")
    public Result<List<Map<String, Object>>> listSlots() {
        return Result.ok(homeSlotService.listAdmin());
    }

    @GetMapping("/admin/home/slots/{code}")
    @Operation(summary = "运营位详情")
    public Result<Map<String, Object>> getSlot(@PathVariable String code) {
        return Result.ok(homeSlotService.getByCode(code));
    }

    @PutMapping("/admin/home/slots/{code}")
    @OpLog(module = "content", action = "home")
    @Operation(summary = "保存运营位")
    public Result<Map<String, Object>> saveSlot(@PathVariable String code,
                                                @RequestBody HomeSlotService.SlotSaveReq req) {
        return Result.ok(homeSlotService.saveByCode(code, req));
    }

    @GetMapping("/admin/reviews")
    @Operation(summary = "评价分页")
    public Result<PageResult<Map<String, Object>>> reviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Integer ratingMax,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(reviewAdminService.page(page, size, rating, ratingMax, status, keyword));
    }

    @PutMapping("/admin/reviews/{id}/status")
    @OpLog(module = "review", action = "status")
    @Operation(summary = "隐藏/恢复评价")
    public Result<Void> reviewStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        reviewAdminService.updateStatus(id, body.get("status"));
        return Result.ok();
    }

    @GetMapping("/admin/in-out")
    @Operation(summary = "出入库分页")
    public Result<PageResult<Map<String, Object>>> inOutPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime to) {
        return Result.ok(inOutOrderService.page(page, size, type, billNo, skuId, from, to));
    }

    @PostMapping("/admin/in-out")
    @OpLog(module = "stock", action = "io")
    @Operation(summary = "创建出入库单")
    public Result<Long> createInOut(@RequestBody InOutOrderService.CreateReq req) {
        return Result.ok(inOutOrderService.createManual(req));
    }
}
