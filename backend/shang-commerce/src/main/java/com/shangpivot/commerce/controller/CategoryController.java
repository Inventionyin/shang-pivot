package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.Attribute;
import com.shangpivot.commerce.entity.Category;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.AttributeMapper;
import com.shangpivot.commerce.mapper.CategoryMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@Tag(name = "类目管理", description = "商品类目的树形查询与增删改")
public class CategoryController {
    private final CategoryMapper categoryMapper;
    private final SpuMapper spuMapper;
    private final AttributeMapper attributeMapper;

    @GetMapping("/tree")
    @Operation(summary = "获取类目树", description = "返回按排序字段排列的多级类目树结构")
    public Result<List<Node>> tree() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getSort));
        Map<Long, List<Category>> grouped = all.stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() == null ? 0L : c.getParentId()));
        return Result.ok(build(grouped, 0L));
    }

    @GetMapping
    @Operation(summary = "获取类目列表", description = "返回扁平化的全部类目列表")
    public Result<List<Category>> list() {
        return Result.ok(categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getSort)));
    }

    @PostMapping
    @OpLog(module = "category", action = "create")
    @Operation(summary = "创建类目", description = "新增商品类目，未传 parentId 时默认为顶级类目")
    public Result<Long> create(@RequestBody Category c) {
        if (!StringUtils.hasText(c.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写类目名称");
        }
        if (c.getParentId() == null) c.setParentId(0L);
        if (c.getSort() == null) c.setSort(0);
        if (c.getLevel() == null) c.setLevel(c.getParentId() == 0L ? 1 : 2);
        if (!StringUtils.hasText(c.getStatus())) c.setStatus("ENABLED");
        categoryMapper.insert(c);
        return Result.ok(c.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "category", action = "update")
    @Operation(summary = "更新类目", description = "修改类目名称、层级、排序及状态")
    public Result<Void> update(
            @Parameter(description = "类目ID", example = "1") @PathVariable Long id,
            @RequestBody Category patch) {
        Category c = require(id);
        c.setName(patch.getName());
        c.setParentId(patch.getParentId());
        c.setLevel(patch.getLevel());
        c.setSort(patch.getSort());
        c.setStatus(patch.getStatus());
        categoryMapper.updateById(c);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "category", action = "delete")
    @Operation(summary = "删除类目", description = "删除指定类目，存在子类目、商品或属性引用时不可删除")
    public Result<Void> delete(
            @Parameter(description = "类目ID", example = "1") @PathVariable Long id) {
        Long children = categoryMapper.selectCount(new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.CONFLICT, "存在子类目，请先处理");
        }
        Long used = spuMapper.selectCount(new LambdaQueryWrapper<Spu>().eq(Spu::getCategoryId, id));
        if (used != null && used > 0) {
            throw new BizException(ErrorCode.CONFLICT, "类目已被商品引用，请先处理引用");
        }
        Long attrs = attributeMapper.selectCount(new LambdaQueryWrapper<Attribute>().eq(Attribute::getCategoryId, id));
        if (attrs != null && attrs > 0) {
            throw new BizException(ErrorCode.CONFLICT, "类目已被属性引用，请先处理引用");
        }
        categoryMapper.deleteById(id);
        return Result.ok();
    }

    private Category require(Long id) {
        Category c = categoryMapper.selectById(id);
        if (c == null) throw new BizException(ErrorCode.NOT_FOUND);
        return c;
    }

    private List<Node> build(Map<Long, List<Category>> grouped, Long parentId) {
        List<Node> list = new ArrayList<>();
        for (Category c : grouped.getOrDefault(parentId, List.of())) {
            Node n = new Node();
            n.setId(c.getId());
            n.setParentId(c.getParentId());
            n.setName(c.getName());
            n.setLevel(c.getLevel());
            n.setSort(c.getSort());
            n.setStatus(c.getStatus());
            n.setChildren(build(grouped, c.getId()));
            list.add(n);
        }
        return list;
    }

    @Data
    @Schema(description = "类目树节点")
    public static class Node {
        @Schema(description = "类目ID", example = "1")
        private Long id;
        @Schema(description = "父类目ID，顶级为 0", example = "0")
        private Long parentId;
        @Schema(description = "类目名称", example = "数码电器")
        private String name;
        @Schema(description = "层级", example = "1")
        private Integer level;
        @Schema(description = "排序值", example = "0")
        private Integer sort;
        @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
        private String status;
        @Schema(description = "子类目列表")
        private List<Node> children = new ArrayList<>();
    }
}
