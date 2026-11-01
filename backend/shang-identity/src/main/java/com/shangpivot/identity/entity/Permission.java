package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "permission", schema = "identity")
public class Permission extends BaseEntity {
    private Long parentId;
    private String code;
    private String name;
    private String type;
    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private Boolean visible;
    private String status;
}
