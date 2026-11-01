package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "review", schema = "commerce", autoResultMap = true)
public class Review extends BaseEntity {
    private Long orderItemId;
    private Long customerId;
    private Long spuId;
    private Integer rating;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> pics;
    private String status;
}
