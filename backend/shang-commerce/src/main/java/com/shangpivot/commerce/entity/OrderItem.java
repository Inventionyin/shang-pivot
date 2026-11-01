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
@TableName(value = "order_item", schema = "commerce", autoResultMap = true)
public class OrderItem extends BaseEntity {
    private Long orderId;
    private Long skuId;
    private Long spuId;
    private String title;
    private String pic;
    private BigDecimal price;
    private Integer quantity;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> specs;
    private BigDecimal subtotal;
}
