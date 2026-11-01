package com.shangpivot.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.entity.AdminUser;
import com.shangpivot.identity.entity.Dept;
import com.shangpivot.identity.mapper.AdminUserMapper;
import com.shangpivot.identity.mapper.DeptMapper;
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
@RequestMapping("/admin/depts")
@RequiredArgsConstructor
@Tag(name = "部门管理", description = "组织架构部门的树形管理与维护")
public class DeptController {
    private final DeptMapper deptMapper;
    private final AdminUserMapper adminUserMapper;

    @GetMapping("/tree")
    @Operation(summary = "部门树", description = "返回完整的部门树结构")
    public Result<List<DeptTree>> tree() {
        List<Dept> all = deptMapper.selectList(new LambdaQueryWrapper<Dept>().orderByAsc(Dept::getSort));
        Map<Long, List<Dept>> grouped = all.stream()
                .collect(Collectors.groupingBy(d -> d.getParentId() == null ? 0L : d.getParentId()));
        return Result.ok(build(grouped, 0L));
    }

    @GetMapping
    @Operation(summary = "部门列表", description = "返回所有部门的扁平列表")
    public Result<List<Dept>> list() {
        return Result.ok(deptMapper.selectList(new LambdaQueryWrapper<Dept>().orderByAsc(Dept::getSort)));
    }

    @PostMapping
    @OpLog(module = "dept", action = "create")
    @Operation(summary = "创建部门", description = "新增组织部门节点")
    public Result<Long> create(@RequestBody Dept dept) {
        if (!StringUtils.hasText(dept.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写部门名称");
        }
        if (dept.getParentId() == null) {
            dept.setParentId(0L);
        }
        if (dept.getSort() == null) {
            dept.setSort(0);
        }
        if (!StringUtils.hasText(dept.getStatus())) {
            dept.setStatus("ENABLED");
        }
        deptMapper.insert(dept);
        return Result.ok(dept.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "dept", action = "update")
    @Operation(summary = "更新部门", description = "修改部门名称、上级部门、排序等信息")
    public Result<Void> update(
            @Parameter(description = "部门ID", example = "1") @PathVariable Long id,
            @RequestBody Dept patch) {
        Dept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        dept.setName(patch.getName());
        dept.setParentId(patch.getParentId());
        dept.setSort(patch.getSort());
        dept.setStatus(patch.getStatus());
        deptMapper.updateById(dept);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "dept", action = "delete")
    @Operation(summary = "删除部门", description = "删除部门节点；存在子部门或用户引用时不允许删除")
    public Result<Void> delete(
            @Parameter(description = "部门ID", example = "1") @PathVariable Long id) {
        Long children = deptMapper.selectCount(new LambdaQueryWrapper<Dept>().eq(Dept::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.CONFLICT, "存在子部门，无法删除");
        }
        Long users = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getDeptId, id));
        if (users != null && users > 0) {
            throw new BizException(ErrorCode.CONFLICT, "部门下仍有用户，无法删除");
        }
        deptMapper.deleteById(id);
        return Result.ok();
    }

    private List<DeptTree> build(Map<Long, List<Dept>> grouped, Long parentId) {
        List<DeptTree> list = new ArrayList<>();
        for (Dept d : grouped.getOrDefault(parentId, List.of())) {
            DeptTree t = new DeptTree();
            t.setId(d.getId());
            t.setParentId(d.getParentId());
            t.setName(d.getName());
            t.setSort(d.getSort());
            t.setStatus(d.getStatus());
            t.setChildren(build(grouped, d.getId()));
            list.add(t);
        }
        return list;
    }

    @Data
    @Schema(description = "部门树节点")
    public static class DeptTree {
        @Schema(description = "部门ID", example = "1")
        private Long id;
        @Schema(description = "上级部门ID，根节点为 0", example = "0")
        private Long parentId;
        @Schema(description = "部门名称", example = "技术部")
        private String name;
        @Schema(description = "排序号", example = "1")
        private Integer sort;
        @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
        private String status;
        @Schema(description = "子部门列表")
        private List<DeptTree> children = new ArrayList<>();
    }
}
