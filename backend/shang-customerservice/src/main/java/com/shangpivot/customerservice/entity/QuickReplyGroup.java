package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 快捷回复分组。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "quick_reply_group", schema = "customerservice")
public class QuickReplyGroup extends BaseEntity {
    private String name;
    private Integer sort;
}
