package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dept", schema = "identity")
public class Dept extends BaseEntity {
    private Long parentId;
    private String name;
    private Integer sort;
    private String status;
}
