package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.Warehouse;
import com.shangpivot.commerce.mapper.WarehouseMapper;
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
@RequestMapping("/admin/warehouses")
@RequiredArgsConstructor
@Tag(name = "仓库管理", description = "仓库的查询与增改")
public class WarehouseController {
    private final WarehouseMapper warehouseMapper;

    @GetMapping
    @Operation(summary = "获取仓库列表", description = "返回全部仓库列表")
    public Result<List<Warehouse>> list() {
        return Result.ok(warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>().orderByAsc(Warehouse::getId)));
    }

    @PostMapping
    @OpLog(module = "warehouse", action = "create")
    @Operation(summary = "创建仓库", description = "新增仓库，未传 status 时默认为 ENABLED")
    public Result<Long> create(@RequestBody Warehouse w) {
        if (!StringUtils.hasText(w.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写仓库名称");
        }
        if (!StringUtils.hasText(w.getStatus())) {
            w.setStatus("ENABLED");
        }
        warehouseMapper.insert(w);
        return Result.ok(w.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "warehouse", action = "update")
    @Operation(summary = "更新仓库", description = "修改仓库名称及状态")
    public Result<Void> update(
            @Parameter(description = "仓库ID", example = "1") @PathVariable Long id,
            @RequestBody Warehouse patch) {
        Warehouse w = warehouseMapper.selectById(id);
        if (w == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        w.setName(patch.getName());
        w.setStatus(patch.getStatus());
        warehouseMapper.updateById(w);
        return Result.ok();
    }
}
