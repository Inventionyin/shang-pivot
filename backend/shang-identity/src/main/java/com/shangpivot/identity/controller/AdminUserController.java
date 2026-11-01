package com.shangpivot.identity.controller;

import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.AdminUserService;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.identity.entity.AdminUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "后台管理员用户的增删改查与状态管理")
public class AdminUserController {
    private final AdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "分页查询用户", description = "按用户名、手机号、状态、部门筛选用户列表")
    public Result<PageResult<Map<String, Object>>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "用户名（模糊匹配）", example = "admin") @RequestParam(required = false) String username,
            @Parameter(description = "手机号（模糊匹配）", example = "13800138000") @RequestParam(required = false) String phone,
            @Parameter(description = "状态：ENABLED / DISABLED", example = "ENABLED") @RequestParam(required = false) String status,
            @Parameter(description = "部门ID", example = "1") @RequestParam(required = false) Long deptId) {
        return Result.ok(adminUserService.page(page, size, username, phone, status, deptId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情", description = "根据用户ID查询用户详情及关联角色")
    public Result<Map<String, Object>> get(
            @Parameter(description = "用户ID", example = "1") @PathVariable Long id) {
        return Result.ok(adminUserService.get(id));
    }

    @PostMapping
    @OpLog(module = "user", action = "create")
    @Operation(summary = "创建用户", description = "新增后台管理员用户并分配角色")
    public Result<Long> create(@RequestBody UserSaveReq req) {
        return Result.ok(adminUserService.create(req.toEntity(), req.getRoleIds()));
    }

    @PutMapping("/{id}")
    @OpLog(module = "user", action = "update")
    @Operation(summary = "更新用户", description = "修改用户基本信息与角色分配")
    public Result<Void> update(
            @Parameter(description = "用户ID", example = "1") @PathVariable Long id,
            @RequestBody UserSaveReq req) {
        adminUserService.update(id, req.toEntity(), req.getRoleIds());
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @OpLog(module = "user", action = "updateStatus")
    @Operation(summary = "更新用户状态", description = "启用或禁用用户")
    public Result<Void> status(
            @Parameter(description = "用户ID", example = "1") @PathVariable Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "状态更新",
                    content = @Content(schema = @Schema(example = "{\"status\":\"ENABLED\"}")))
            @RequestBody Map<String, String> body) {
        adminUserService.updateStatus(id, body.get("status"));
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    @OpLog(module = "user", action = "resetPassword")
    @Operation(summary = "重置密码", description = "管理员重置指定用户的登录密码")
    public Result<Void> resetPassword(
            @Parameter(description = "用户ID", example = "1") @PathVariable Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "新密码",
                    content = @Content(schema = @Schema(example = "{\"password\":\"Admin@123\"}")))
            @RequestBody Map<String, String> body) {
        adminUserService.resetPassword(id, body.get("password"));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @OpLog(module = "user", action = "delete")
    @Operation(summary = "删除用户", description = "根据用户ID删除用户")
    public Result<Void> delete(
            @Parameter(description = "用户ID", example = "1") @PathVariable Long id) {
        adminUserService.delete(id);
        return Result.ok();
    }

    @Data
    @Schema(description = "用户保存请求")
    public static class UserSaveReq {
        @Schema(description = "用户名", example = "zhangsan")
        private String username;
        @Schema(description = "密码（创建时必填）", example = "Admin@123")
        private String password;
        @Schema(description = "真实姓名", example = "张三")
        private String realName;
        @Schema(description = "手机号", example = "13800138000")
        private String phone;
        @Schema(description = "邮箱", example = "zhangsan@example.com")
        private String email;
        @Schema(description = "部门ID", example = "1")
        private Long deptId;
        @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
        private String status;
        @Schema(description = "角色ID列表", example = "[1, 2]")
        private List<Long> roleIds;

        AdminUser toEntity() {
            AdminUser u = new AdminUser();
            u.setUsername(username);
            u.setPassword(password);
            u.setRealName(realName);
            u.setPhone(phone);
            u.setEmail(email);
            u.setDeptId(deptId);
            u.setStatus(status);
            return u;
        }
    }
}
