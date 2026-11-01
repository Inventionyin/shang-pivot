package com.shangpivot.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.PermissionQueryService;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.identity.entity.Permission;
import com.shangpivot.identity.mapper.PermissionMapper;
import com.shangpivot.identity.vo.MenuTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单/权限树 CRUD API（目录·菜单·按钮）。
 * <p>路径前缀 /admin/permissions。
 */
@RestController
@RequestMapping("/admin/permissions")
@RequiredArgsConstructor
@Tag(name = "菜单权限", description = "菜单与按钮权限的树形管理与维护")
public class PermissionController {
    private final PermissionMapper permissionMapper;
    private final PermissionQueryService permissionQueryService;

    @GetMapping("/tree")
    @Operation(summary = "权限树", description = "返回完整的菜单权限树结构")
    public Result<List<MenuTreeVO>> tree() {
        return Result.ok(permissionQueryService.buildFullPermissionTree());
    }

    @GetMapping
    @Operation(summary = "权限列表", description = "返回所有权限的扁平列表")
    public Result<List<Permission>> list() {
        return Result.ok(permissionMapper.selectList(new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getSort)));
    }

    @PostMapping
    @OpLog(module = "permission", action = "create")
    @Operation(summary = "创建权限", description = "新增菜单、目录或按钮权限节点")
    public Result<Long> create(@RequestBody Permission permission) {
        // identity.permission.code / name / type 为 NOT NULL；缺失时应返回参数错误而非落库 500
        if (!StringUtils.hasText(permission.getCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写权限编码");
        }
        if (!StringUtils.hasText(permission.getName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写权限名称");
        }
        if (!StringUtils.hasText(permission.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写权限类型");
        }
        Long cnt = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>().eq(Permission::getCode, permission.getCode()));
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.CONFLICT, "权限编码已存在");
        }
        if (permission.getParentId() == null) {
            permission.setParentId(0L);
        }
        if (permission.getSort() == null) {
            permission.setSort(0);
        }
        if (permission.getVisible() == null) {
            permission.setVisible(true);
        }
        if (!StringUtils.hasText(permission.getStatus())) {
            permission.setStatus("ENABLED");
        }
        permissionMapper.insert(permission);
        return Result.ok(permission.getId());
    }

    @PutMapping("/{id}")
    @OpLog(module = "permission", action = "update")
    @Operation(summary = "更新权限", description = "修改权限节点的名称、路径、图标等信息")
    public Result<Void> update(
            @Parameter(description = "权限ID", example = "1") @PathVariable Long id,
            @RequestBody Permission patch) {
        Permission p = permissionMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        p.setName(patch.getName());
        p.setType(patch.getType());
        p.setPath(patch.getPath());
        p.setComponent(patch.getComponent());
        p.setIcon(patch.getIcon());
        p.setSort(patch.getSort());
        p.setVisible(patch.getVisible());
        p.setStatus(patch.getStatus());
        p.setParentId(patch.getParentId());
        if (StringUtils.hasText(patch.getCode())) {
            p.setCode(patch.getCode());
        }
        permissionMapper.updateById(p);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "permission", action = "delete")
    @Operation(summary = "删除权限", description = "删除权限节点，存在子节点时不允许删除")
    public Result<Void> delete(
            @Parameter(description = "权限ID", example = "1") @PathVariable Long id) {
        Long children = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>().eq(Permission::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.CONFLICT, "存在子节点，无法删除");
        }
        permissionMapper.deleteById(id);
        return Result.ok();
    }
}
