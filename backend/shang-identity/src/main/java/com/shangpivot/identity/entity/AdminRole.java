package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 管理员-角色关联（复合主键）。MyBatis-Plus 要求至少一个 {@code @TableId}；
 * 业务只用 Wrapper 增删查，不调用 xxById。
 */
@Data
@TableName(value = "admin_role", schema = "identity")
public class AdminRole {
    @TableId(type = IdType.INPUT)
    private Long adminUserId;
    private Long roleId;
}
