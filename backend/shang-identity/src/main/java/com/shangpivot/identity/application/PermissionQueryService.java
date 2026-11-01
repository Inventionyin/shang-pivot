package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.identity.entity.AdminRole;
import com.shangpivot.identity.entity.Permission;
import com.shangpivot.identity.entity.Role;
import com.shangpivot.identity.entity.RolePermission;
import com.shangpivot.identity.mapper.AdminRoleMapper;
import com.shangpivot.identity.mapper.PermissionMapper;
import com.shangpivot.identity.mapper.RoleMapper;
import com.shangpivot.identity.mapper.RolePermissionMapper;
import com.shangpivot.identity.vo.MenuTreeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限查询与绑定：用户→角色→权限码，以及菜单树组装。
 * <p>关联表：admin_role、role、role_permission、permission。
 */
@Service
@RequiredArgsConstructor
public class PermissionQueryService {

    private final AdminRoleMapper adminRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;

    /** 用户绑定的角色 ID 集合。 */
    public Set<Long> listRoleIds(Long userId) {
        return adminRoleMapper.selectList(new LambdaQueryWrapper<AdminRole>()
                        .eq(AdminRole::getAdminUserId, userId))
                .stream().map(AdminRole::getRoleId).collect(Collectors.toSet());
    }

    /** 用户角色编码集合（如 SUPER_ADMIN）。 */
    public Set<String> listRoleCodes(Long userId) {
        Set<Long> roleIds = listRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream().map(Role::getCode).collect(Collectors.toSet());
    }

    /** 用户经角色聚合后的全部权限实体（含按钮等）。 */
    public List<Permission> listPermissions(Long userId) {
        Set<Long> roleIds = listRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> permIds = rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, roleIds))
                .stream().map(RolePermission::getPermissionId).collect(Collectors.toSet());
        if (permIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectBatchIds(permIds);
    }

    /** 用户权限码集合，供鉴权与前端按钮控制。 */
    public Set<String> listPermissionCodes(Long userId) {
        return listPermissions(userId).stream().map(Permission::getCode).collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 按用户权限组装侧边栏菜单树：仅 DIR/MENU、可见且启用，按 sort 排序。
     */
    public List<MenuTreeVO> buildMenuTree(Long userId) {
        List<Permission> permissions = listPermissions(userId).stream()
                .filter(p -> "DIR".equals(p.getType()) || "MENU".equals(p.getType()))
                .filter(p -> Boolean.TRUE.equals(p.getVisible()))
                .filter(p -> p.getStatus() == null || "ENABLED".equals(p.getStatus()))
                .sorted(Comparator.comparing(p -> p.getSort() == null ? 0 : p.getSort()))
                .toList();
        return toTree(permissions, 0L);
    }

    /** 全量权限树（含按钮），供角色授权勾选。 */
    public List<MenuTreeVO> buildFullPermissionTree() {
        List<Permission> all = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .orderByAsc(Permission::getSort));
        return toTree(all, 0L);
    }

    private List<MenuTreeVO> toTree(List<Permission> list, Long parentId) {
        Map<Long, List<Permission>> grouped = list.stream()
                .collect(Collectors.groupingBy(p -> p.getParentId() == null ? 0L : p.getParentId()));
        return buildChildren(grouped, parentId);
    }

    private List<MenuTreeVO> buildChildren(Map<Long, List<Permission>> grouped, Long parentId) {
        List<Permission> children = grouped.getOrDefault(parentId, List.of());
        List<MenuTreeVO> result = new ArrayList<>();
        for (Permission p : children) {
            MenuTreeVO vo = new MenuTreeVO();
            vo.setId(p.getId());
            vo.setParentId(p.getParentId());
            vo.setCode(p.getCode());
            vo.setName(p.getName());
            vo.setType(p.getType());
            vo.setPath(p.getPath());
            vo.setComponent(p.getComponent());
            vo.setIcon(p.getIcon());
            vo.setSort(p.getSort());
            vo.setVisible(p.getVisible());
            vo.setChildren(buildChildren(grouped, p.getId()));
            result.add(vo);
        }
        return result;
    }

    /**
     * 全量替换角色权限：先删后插；permissionIds 为 null 则仅清空。
     */
    public void bindRolePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        if (permissionIds == null) {
            return;
        }
        for (Long pid : permissionIds.stream().filter(Objects::nonNull).distinct().toList()) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            rolePermissionMapper.insert(rp);
        }
    }

    /**
     * 全量替换用户角色：先删后插；roleIds 为 null 则仅清空。
     */
    public void bindUserRoles(Long userId, List<Long> roleIds) {
        adminRoleMapper.delete(new LambdaQueryWrapper<AdminRole>().eq(AdminRole::getAdminUserId, userId));
        if (roleIds == null) {
            return;
        }
        for (Long rid : roleIds.stream().filter(Objects::nonNull).distinct().toList()) {
            AdminRole ar = new AdminRole();
            ar.setAdminUserId(userId);
            ar.setRoleId(rid);
            adminRoleMapper.insert(ar);
        }
    }

    public List<Long> listPermissionIdsByRole(Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId))
                .stream().map(RolePermission::getPermissionId).toList();
    }

    public long countUsersByRole(Long roleId) {
        Long cnt = adminRoleMapper.selectCount(new LambdaQueryWrapper<AdminRole>()
                .eq(AdminRole::getRoleId, roleId));
        return cnt == null ? 0L : cnt;
    }

    public List<Long> listRoleIdsByUser(Long userId) {
        return new ArrayList<>(listRoleIds(userId));
    }
}
