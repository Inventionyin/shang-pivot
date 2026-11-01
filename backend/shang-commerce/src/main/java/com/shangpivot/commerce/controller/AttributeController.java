package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.application.AttributeService;
import com.shangpivot.commerce.entity.Attribute;
import com.shangpivot.commerce.mapper.AttributeMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/attributes")
@RequiredArgsConstructor
@Tag(name = "属性管理", description = "商品规格/属性项的查询与增删改")
public class AttributeController {
    private final AttributeMapper attributeMapper;
    private final AttributeService attributeService;

    @GetMapping
    @Operation(summary = "查询属性列表", description = "按类目筛选（含子孙类目）；不传 categoryId 时返回全部")
    public Result<List<Attribute>> list(
            @Parameter(description = "类目ID", example = "1") @RequestParam(required = false) Long categoryId) {
        return Result.ok(attributeService.listByCategory(categoryId));
    }

    @PostMapping
    @OpLog(module = "attribute", action = "create")
    @Operation(summary = "创建属性", description = "新增商品属性项，未传 type 时默认为 SPEC")
    public Result<Long> create(@RequestBody Attribute attr) {
        // commerce.attribute.category_id / name 为 NOT NULL，缺失时应在入口拒绝而非落库报错
        if (attr.getCategoryId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择类目");
        }
        if (!StringUtils.hasText(attr.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写属性名称");
        }
        if (!StringUtils.hasText(attr.getType())) attr.setType("SPEC");
        if (attr.getSort() == null) attr.setSort(0);
        attributeMapper.insert(attr);
        return Result.ok(attr.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "attribute", action = "update")
    @Operation(summary = "更新属性", description = "修改属性名称、类型、选项、排序及所属类目")
    public Result<Void> update(
            @Parameter(description = "属性ID", example = "1") @PathVariable Long id,
            @RequestBody Attribute patch) {
        Attribute attr = attributeMapper.selectById(id);
        if (attr == null) throw new BizException(ErrorCode.NOT_FOUND);
        attr.setName(patch.getName());
        attr.setType(patch.getType());
        attr.setOptions(patch.getOptions());
        attr.setSort(patch.getSort());
        attr.setCategoryId(patch.getCategoryId());
        attributeMapper.updateById(attr);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "attribute", action = "delete")
    @Operation(summary = "删除属性", description = "根据属性ID删除属性项")
    public Result<Void> delete(
            @Parameter(description = "属性ID", example = "1") @PathVariable Long id) {
        attributeMapper.deleteById(id);
        return Result.ok();
    }
}
