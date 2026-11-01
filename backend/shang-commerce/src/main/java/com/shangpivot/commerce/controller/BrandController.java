package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.application.BrandService;
import com.shangpivot.commerce.entity.Brand;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.BrandMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/brands")
@RequiredArgsConstructor
@Tag(name = "品牌管理", description = "商品品牌的分页查询与增删改")
public class BrandController {
    private final BrandMapper brandMapper;
    private final BrandService brandService;
    private final SpuMapper spuMapper;

    @GetMapping
    @Operation(summary = "分页查询品牌", description = "可按名称、类目（经商品关联）筛选；返回关联 SPU 数")
    public Result<PageResult<BrandService.BrandListItem>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "品牌名称（模糊匹配）", example = "华为") @RequestParam(required = false) String name,
            @Parameter(description = "类目ID：仅返回该类目树下有商品关联的品牌", example = "10")
            @RequestParam(required = false) Long categoryId) {
        return Result.ok(brandService.page(page, size, name, categoryId));
    }

    @PostMapping
    @OpLog(module = "brand", action = "create")
    @Operation(summary = "创建品牌", description = "新增商品品牌")
    public Result<Long> create(@RequestBody Brand brand) {
        if (!StringUtils.hasText(brand.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写品牌名称");
        }
        if (brand.getCategoryId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择类目");
        }
        if (brand.getSort() == null) brand.setSort(0);
        if (!StringUtils.hasText(brand.getStatus())) brand.setStatus("ENABLED");
        brandMapper.insert(brand);
        return Result.ok(brand.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "brand", action = "update")
    @Operation(summary = "更新品牌", description = "修改品牌名称、Logo、排序及状态")
    public Result<Void> update(
            @Parameter(description = "品牌ID", example = "1") @PathVariable Long id,
            @RequestBody Brand patch) {
        Brand brand = brandMapper.selectById(id);
        if (brand == null) throw new BizException(ErrorCode.NOT_FOUND);
        brand.setName(patch.getName());
        brand.setLogo(patch.getLogo());
        brand.setSort(patch.getSort());
        brand.setStatus(patch.getStatus());
        brand.setCategoryId(patch.getCategoryId());
        brandMapper.updateById(brand);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "brand", action = "delete")
    @Operation(summary = "删除品牌", description = "根据品牌ID删除品牌；被商品引用时不可删除")
    public Result<Void> delete(
            @Parameter(description = "品牌ID", example = "1") @PathVariable Long id) {
        Long used = spuMapper.selectCount(new LambdaQueryWrapper<Spu>().eq(Spu::getBrandId, id));
        if (used != null && used > 0) {
            throw new BizException(ErrorCode.CONFLICT, "品牌已被商品引用，请先处理引用");
        }
        brandMapper.deleteById(id);
        return Result.ok();
    }
}
