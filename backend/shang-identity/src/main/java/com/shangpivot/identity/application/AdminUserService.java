package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.identity.entity.AdminUser;
import com.shangpivot.identity.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理员账号用例：CRUD、启停、重置密码与角色绑定。
 * <p>关联表：admin_user、admin_role；id=1 超管不可禁用/删除。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final AdminUserMapper adminUserMapper;
    private final PermissionQueryService permissionQueryService;
    private final PasswordEncoder passwordEncoder;

    /** 分页查询，附带 roleIds。 */
    public PageResult<Map<String, Object>> page(int page, int size, String username, String phone, String status, Long deptId) {
        LambdaQueryWrapper<AdminUser> q = new LambdaQueryWrapper<>();
        // 「关键字」一个输入框要同时匹配用户名或手机号（PRD-V1 §8.2），
        // 因此两个 like 用 OR 分组；分开传 username/phone 时仍各自生效。
        q.and(StringUtils.hasText(username) || StringUtils.hasText(phone),
                w -> w.like(StringUtils.hasText(username), AdminUser::getUsername, username)
                        .or()
                        .like(StringUtils.hasText(phone), AdminUser::getPhone, phone));
        q.eq(StringUtils.hasText(status), AdminUser::getStatus, status)
                .eq(deptId != null, AdminUser::getDeptId, deptId)
                .orderByDesc(AdminUser::getId);
        Page<AdminUser> p = adminUserMapper.selectPage(new Page<>(page, size), q);
        List<Map<String, Object>> records = p.getRecords().stream().map(u -> {
            Map<String, Object> m = toMap(u);
            m.put("roleIds", permissionQueryService.listRoleIdsByUser(u.getId()));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    public Map<String, Object> get(Long id) {
        AdminUser u = require(id);
        Map<String, Object> m = toMap(u);
        m.put("roleIds", permissionQueryService.listRoleIdsByUser(id));
        return m;
    }

    /**
     * 创建管理员：username 唯一；密码缺省 Admin@123 并 BCrypt 入库，再绑定角色。
     */
    @Transactional
    public Long create(AdminUser user, List<Long> roleIds) {
        Long cnt = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, user.getUsername()));
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword("Admin@123");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (!StringUtils.hasText(user.getStatus())) {
            user.setStatus("ENABLED");
        }
        adminUserMapper.insert(user);
        permissionQueryService.bindUserRoles(user.getId(), roleIds);
        return user.getId();
    }

    /** 更新资料；roleIds 非 null 时全量重绑角色（不改密码）。 */
    @Transactional
    public void update(Long id, AdminUser patch, List<Long> roleIds) {
        AdminUser user = require(id);
        user.setRealName(patch.getRealName());
        user.setPhone(patch.getPhone());
        user.setEmail(patch.getEmail());
        user.setDeptId(patch.getDeptId());
        if (StringUtils.hasText(patch.getStatus())) {
            user.setStatus(patch.getStatus());
        }
        adminUserMapper.updateById(user);
        if (roleIds != null) {
            permissionQueryService.bindUserRoles(id, roleIds);
        }
    }

    /** 启停账号；超管（id=1）不可禁用。 */
    public void updateStatus(Long id, String status) {
        if (id == 1L) {
            throw new BizException(ErrorCode.CONFLICT, "超管不可禁用");
        }
        AdminUser user = require(id);
        user.setStatus(status);
        adminUserMapper.updateById(user);
    }

    /** 重置密码；rawPassword 为空则回落默认 Admin@123。 */
    public void resetPassword(Long id, String rawPassword) {
        AdminUser user = require(id);
        user.setPassword(passwordEncoder.encode(StringUtils.hasText(rawPassword) ? rawPassword : "Admin@123"));
        adminUserMapper.updateById(user);
    }

    /** 删除账号；超管（id=1）不可删除。 */
    public void delete(Long id) {
        if (id == 1L) {
            throw new BizException(ErrorCode.CONFLICT, "超管不可删除");
        }
        require(id);
        permissionQueryService.bindUserRoles(id, List.of());
        adminUserMapper.deleteById(id);
    }

    private AdminUser require(Long id) {
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private Map<String, Object> toMap(AdminUser u) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("realName", u.getRealName());
        m.put("phone", u.getPhone());
        m.put("email", u.getEmail());
        m.put("deptId", u.getDeptId());
        m.put("status", u.getStatus());
        m.put("lastLoginAt", u.getLastLoginAt());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
