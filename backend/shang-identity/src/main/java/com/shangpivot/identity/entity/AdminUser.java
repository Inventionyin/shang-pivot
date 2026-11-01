package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "admin_user", schema = "identity")
public class AdminUser extends BaseEntity {
    private String username;
    private String password;
    private String realName;
    private String phone;
    private String email;
    private Long deptId;
    private String status;
    private OffsetDateTime lastLoginAt;
}
