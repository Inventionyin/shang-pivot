package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "role", schema = "identity")
public class Role extends BaseEntity {
    private String code;
    private String name;
    private String dataScope;
    private String status;
    private String remark;
}
