package com.shangpivot.identity.controller;

import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.RoleService;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.identity.entity.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 角色管理 API：分页、全量列表与权限分配。
 * <p>路径前缀 /admin/roles。
 */
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
@Tag(name = "角色管理", description = "后台角色的增删改查与权限分配")
public class RoleController {
    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "分页查询角色", description = "按角色名称、编码筛选角色列表")
    public Result<PageResult<Map<String, Object>>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "角色名称（模糊匹配）", example = "管理员") @RequestParam(required = false) String name,
            @Parameter(description = "角色编码（模糊匹配）", example = "ADMIN") @RequestParam(required = false) String code) {
        return Result.ok(roleService.page(page, size, name, code));
    }

    @GetMapping("/all")
    @Operation(summary = "查询全部角色", description = "返回所有角色，用于下拉选择")
    public Result<List<Role>> all() {
        return Result.ok(roleService.listAll());
    }

    @PostMapping
    @OpLog(module = "role", action = "create")
    @Operation(summary = "创建角色", description = "新增角色并分配菜单权限")
    public Result<Long> create(@RequestBody RoleSaveReq req) {
        Role role = new Role();
        role.setCode(req.getCode());
        role.setName(req.getName());
        role.setDataScope(req.getDataScope());
        role.setStatus(req.getStatus());
        role.setRemark(req.getRemark());
        return Result.ok(roleService.create(role, req.getPermissionIds()));
    }

    @PutMapping("/{id}")
    @OpLog(module = "role", action = "update")
    @Operation(summary = "更新角色", description = "修改角色信息与权限分配")
    public Result<Void> update(
            @Parameter(description = "角色ID", example = "1") @PathVariable Long id,
            @RequestBody RoleSaveReq req) {
        Role role = new Role();
        role.setName(req.getName());
        role.setDataScope(req.getDataScope());
        role.setStatus(req.getStatus());
        role.setRemark(req.getRemark());
        roleService.update(id, role, req.getPermissionIds());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "role", action = "delete")
    @Operation(summary = "删除角色", description = "根据角色ID删除角色")
    public Result<Void> delete(
            @Parameter(description = "角色ID", example = "1") @PathVariable Long id) {
        roleService.delete(id);
        return Result.ok();
    }

    @Data
    @Schema(description = "角色保存请求")
    public static class RoleSaveReq {
        @Schema(description = "角色编码（创建时必填）", example = "OPERATOR")
        private String code;
        @Schema(description = "角色名称", example = "运营人员")
        private String name;
        @Schema(description = "数据权限范围：ALL / DEPT / SELF", example = "ALL")
        private String dataScope;
        @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
        private String status;
        @Schema(description = "备注", example = "负责日常运营")
        private String remark;
        @Schema(description = "权限ID列表", example = "[1, 2, 3]")
        private List<Long> permissionIds;
    }
}
