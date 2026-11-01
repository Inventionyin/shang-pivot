package com.shangpivot.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.entity.Dict;
import com.shangpivot.identity.entity.DictItem;
import com.shangpivot.identity.mapper.DictItemMapper;
import com.shangpivot.identity.mapper.DictMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "字典管理", description = "数据字典及其字典项的增删改查")
public class DictController {
    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;

    @GetMapping("/admin/dicts")
    @Operation(summary = "分页查询字典", description = "按字典编码、名称筛选字典列表")
    public Result<PageResult<Dict>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "字典编码（模糊匹配）", example = "user_status") @RequestParam(required = false) String code,
            @Parameter(description = "字典名称（模糊匹配）", example = "用户状态") @RequestParam(required = false) String name) {
        LambdaQueryWrapper<Dict> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(code), Dict::getCode, code)
                .like(StringUtils.hasText(name), Dict::getName, name)
                .orderByDesc(Dict::getId);
        Page<Dict> p = dictMapper.selectPage(new Page<>(page, size), q);
        return Result.ok(PageResult.of(p.getTotal(), p.getRecords()));
    }

    @PostMapping("/admin/dicts")
    @OpLog(module = "dict", action = "create")
    @Operation(summary = "创建字典", description = "新增数据字典")
    public Result<Long> createDict(@RequestBody Dict dict) {
        if (!StringUtils.hasText(dict.getCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典编码");
        }
        if (!StringUtils.hasText(dict.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典名称");
        }
        Long dup = dictMapper.selectCount(new LambdaQueryWrapper<Dict>().eq(Dict::getCode, dict.getCode()));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CONFLICT, "字典编码已存在");
        }
        dictMapper.insert(dict);
        return Result.ok(dict.getId());
    }

    @PutMapping("/admin/dicts/{id}")
    @OpLog(module = "dict", action = "update")
    @Operation(summary = "更新字典", description = "修改字典名称与备注")
    public Result<Void> updateDict(
            @Parameter(description = "字典ID", example = "1") @PathVariable Long id,
            @RequestBody Dict patch) {
        Dict dict = dictMapper.selectById(id);
        if (dict == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        dict.setName(patch.getName());
        dict.setRemark(patch.getRemark());
        dictMapper.updateById(dict);
        return Result.ok();
    }

    @DeleteMapping("/admin/dicts/{id}")
    @OpLog(module = "dict", action = "delete")
    @Operation(summary = "删除字典", description = "删除字典及其所有字典项")
    public Result<Void> deleteDict(
            @Parameter(description = "字典ID", example = "1") @PathVariable Long id) {
        dictItemMapper.delete(new LambdaQueryWrapper<DictItem>().eq(DictItem::getDictId, id));
        dictMapper.deleteById(id);
        return Result.ok();
    }

    @GetMapping("/admin/dict-items")
    @Operation(summary = "查询字典项", description = "根据字典ID查询字典项列表")
    public Result<List<DictItem>> items(
            @Parameter(description = "字典ID", example = "1", required = true) @RequestParam Long dictId) {
        return Result.ok(dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictId, dictId)
                .orderByAsc(DictItem::getSort)));
    }

    @GetMapping("/admin/dict-items/by-code")
    @Operation(summary = "按编码查询字典项", description = "根据字典编码查询字典项列表")
    public Result<List<DictItem>> itemsByCode(
            @Parameter(description = "字典编码", example = "user_status", required = true) @RequestParam String code) {
        Dict dict = dictMapper.selectOne(new LambdaQueryWrapper<Dict>().eq(Dict::getCode, code));
        if (dict == null) {
            return Result.ok(List.of());
        }
        return items(dict.getId());
    }

    @PostMapping("/admin/dict-items")
    @OpLog(module = "dict", action = "createItem")
    @Operation(summary = "创建字典项", description = "为指定字典新增字典项")
    public Result<Long> createItem(@RequestBody DictItem item) {
        if (item.getDictId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择所属字典");
        }
        if (!StringUtils.hasText(item.getLabel())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典项标签");
        }
        if (!StringUtils.hasText(item.getValue())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写字典项值");
        }
        if (item.getSort() == null) {
            item.setSort(0);
        }
        if (!StringUtils.hasText(item.getStatus())) {
            item.setStatus("ENABLED");
        }
        dictItemMapper.insert(item);
        return Result.ok(item.getId());
    }

    @PutMapping("/admin/dict-items/{id}")
    @OpLog(module = "dict", action = "updateItem")
    @Operation(summary = "更新字典项", description = "修改字典项的标签、值、排序等信息")
    public Result<Void> updateItem(
            @Parameter(description = "字典项ID", example = "1") @PathVariable Long id,
            @RequestBody DictItem patch) {
        DictItem item = dictItemMapper.selectById(id);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        item.setLabel(patch.getLabel());
        item.setValue(patch.getValue());
        item.setSort(patch.getSort());
        item.setStatus(patch.getStatus());
        dictItemMapper.updateById(item);
        return Result.ok();
    }

    @DeleteMapping("/admin/dict-items/{id}")
    @OpLog(module = "dict", action = "deleteItem")
    @Operation(summary = "删除字典项", description = "根据字典项ID删除")
    public Result<Void> deleteItem(
            @Parameter(description = "字典项ID", example = "1") @PathVariable Long id) {
        dictItemMapper.deleteById(id);
        return Result.ok();
    }
}
