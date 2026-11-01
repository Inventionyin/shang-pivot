package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-权限关联（复合主键）。MyBatis-Plus 要求至少一个 {@code @TableId}；
 * 业务只用 Wrapper 增删查，不调用 xxById。
 */
@Data
@TableName(value = "role_permission", schema = "identity")
public class RolePermission {
    @TableId(type = IdType.INPUT)
    private Long roleId;
    private Long permissionId;
}
