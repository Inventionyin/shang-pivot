package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.identity.entity.Role;
import com.shangpivot.identity.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色 CRUD 与权限绑定。
 * <p>关联表：role、role_permission；id=1 超管角色不可删。
 */
@Service
@RequiredArgsConstructor
public class RoleService {
    private final RoleMapper roleMapper;
    private final PermissionQueryService permissionQueryService;

    /** 分页查询，附带 permissionIds 与绑定用户数。 */
    public PageResult<Map<String, Object>> page(int page, int size, String name, String code) {
        LambdaQueryWrapper<Role> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(name), Role::getName, name)
                .like(StringUtils.hasText(code), Role::getCode, code)
                .orderByDesc(Role::getId);
        Page<Role> p = roleMapper.selectPage(new Page<>(page, size), q);
        List<Map<String, Object>> records = p.getRecords().stream().map(r -> {
            Map<String, Object> m = toMap(r);
            m.put("permissionIds", permissionQueryService.listPermissionIdsByRole(r.getId()));
            m.put("userCount", permissionQueryService.countUsersByRole(r.getId()));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    public List<Role> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
    }

    /** 新建角色并绑定权限；code 唯一，默认 ENABLED / dataScope=ALL。 */
    @Transactional
    public Long create(Role role, List<Long> permissionIds) {
        Long cnt = roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getCode, role.getCode()));
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        if (!StringUtils.hasText(role.getStatus())) {
            role.setStatus("ENABLED");
        }
        if (!StringUtils.hasText(role.getDataScope())) {
            role.setDataScope("ALL");
        }
        roleMapper.insert(role);
        permissionQueryService.bindRolePermissions(role.getId(), permissionIds);
        return role.getId();
    }

    /** 更新角色；permissionIds 非 null 时全量重绑权限。 */
    @Transactional
    public void update(Long id, Role patch, List<Long> permissionIds) {
        Role role = require(id);
        role.setName(patch.getName());
        role.setDataScope(patch.getDataScope());
        role.setStatus(patch.getStatus());
        role.setRemark(patch.getRemark());
        roleMapper.updateById(role);
        if (permissionIds != null) {
            permissionQueryService.bindRolePermissions(id, permissionIds);
        }
    }

    /** 删除角色；超管角色（id=1）禁止删除；有用户绑定时拒绝。 */
    public void delete(Long id) {
        if (id == 1L) {
            throw new BizException(ErrorCode.CONFLICT, "超管角色不可删除");
        }
        if (permissionQueryService.countUsersByRole(id) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "角色已被用户引用，请先解除绑定");
        }
        roleMapper.deleteById(id);
    }

    private Role require(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private Map<String, Object> toMap(Role r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("dataScope", r.getDataScope());
        m.put("status", r.getStatus());
        m.put("remark", r.getRemark());
        return m;
    }
}
