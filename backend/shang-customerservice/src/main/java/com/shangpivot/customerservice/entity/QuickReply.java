package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 快捷回复话术；is_recommended=true 的行即 C 端推荐问。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "quick_reply", schema = "customerservice")
public class QuickReply extends BaseEntity {
    private Long groupId;
    private String title;
    private String content;
    private Integer sort;
    private Boolean isRecommended;
}
