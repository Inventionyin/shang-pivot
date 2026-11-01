package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "shipment", schema = "commerce", autoResultMap = true)
public class Shipment extends BaseEntity {
    private Long orderId;
    private String carrier;
    private String trackingNo;
    private String status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> traces;
}
