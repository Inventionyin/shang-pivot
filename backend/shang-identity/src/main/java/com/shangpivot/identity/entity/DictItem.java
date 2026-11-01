package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dict_item", schema = "identity")
public class DictItem extends BaseEntity {
    private Long dictId;
    private String label;
    private String value;
    private Integer sort;
    private String status;
}
