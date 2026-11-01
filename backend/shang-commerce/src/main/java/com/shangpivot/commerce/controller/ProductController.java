package com.shangpivot.commerce.controller;

import com.shangpivot.commerce.application.ProductService;
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
 * 商品（SPU/SKU）管理 API：查询、创建更新、上下架与删除。
 * <p>路径前缀 /admin/products。
 */
@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
@Tag(name = "商品管理", description = "SPU/SKU 商品的查询、创建、上下架与删除")
public class ProductController {
    private final ProductService productService;

    @GetMapping
    @Operation(summary = "分页查询商品", description = "按名称、状态、类目筛选；返回类目名、SKU 数、库存与价格区间")
    public Result<PageResult<ProductService.SpuListItem>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "商品名称（模糊匹配）", example = "手机") @RequestParam(required = false) String name,
            @Parameter(description = "商品状态：ON / OFF / DRAFT", example = "ON") @RequestParam(required = false) String status,
            @Parameter(description = "类目ID", example = "1") @RequestParam(required = false) Long categoryId) {
        return Result.ok(productService.page(page, size, name, status, categoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取商品详情", description = "返回 SPU 信息及关联 SKU 列表")
    public Result<Map<String, Object>> detail(
            @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
        return Result.ok(productService.detail(id));
    }

    @PostMapping
    @OpLog(module = "product", action = "create")
    @Operation(summary = "创建商品", description = "新建 SPU 及 SKU，默认状态为 DRAFT")
    public Result<Long> create(@RequestBody ProductService.ProductSaveReq req) {
        return Result.ok(productService.create(req));
    }

    @PutMapping("/{id}")
    @OpLog(module = "product", action = "update")
    @Operation(summary = "更新商品", description = "修改 SPU 信息并重建 SKU 列表")
    public Result<Void> update(
            @Parameter(description = "商品ID", example = "1") @PathVariable Long id,
            @RequestBody ProductService.ProductSaveReq req) {
        productService.update(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/on")
    @OpLog(module = "product", action = "onShelf")
    @Operation(summary = "商品上架", description = "将商品状态变更为 ON，需满足主图、SKU、价格等校验")
    public Result<Void> on(
            @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
        productService.changeStatus(id, "ON");
        return Result.ok();
    }

    @PutMapping("/{id}/off")
    @OpLog(module = "product", action = "offShelf")
    @Operation(summary = "商品下架", description = "将商品状态变更为 OFF")
    public Result<Void> off(
            @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
        productService.changeStatus(id, "OFF");
        return Result.ok();
    }

    @PutMapping("/batch-off")
    @OpLog(module = "product", action = "batchOff")
    @Operation(summary = "批量下架", description = "将勾选的上架商品批量下架")
    public Result<Integer> batchOff(@RequestBody java.util.List<Long> ids) {
        return Result.ok(productService.batchOff(ids));
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "product", action = "delete")
    @Operation(summary = "删除商品", description = "仅草稿状态商品可删除")
    public Result<Void> delete(
            @Parameter(description = "商品ID", example = "1") @PathVariable Long id) {
        productService.delete(id);
        return Result.ok();
    }
}
