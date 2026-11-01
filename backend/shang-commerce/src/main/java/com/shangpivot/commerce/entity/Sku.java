package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sku", schema = "commerce", autoResultMap = true)
public class Sku extends BaseEntity {
    private Long spuId;
    private String title;
    private BigDecimal price;
    private BigDecimal costPrice;
    private String barCode;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> specs;
    private String pic;
    private String status;
}
