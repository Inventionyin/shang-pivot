package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "customer", schema = "identity")
public class Customer extends BaseEntity {
    private String openid;
    private String unionId;
    private String phone;
    private String email;
    private String nickname;
    private String avatar;
    private Integer gender;
    private String status;
    private OffsetDateTime lastLoginAt;
}
