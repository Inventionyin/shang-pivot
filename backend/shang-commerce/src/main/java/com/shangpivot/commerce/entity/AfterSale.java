package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "after_sale", schema = "commerce", autoResultMap = true)
public class AfterSale extends BaseEntity {
    private String afterSaleNo;
    private Long orderId;
    private Long customerId;
    private String type;
    private String reason;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> evidencePics;
    private String status;
    private BigDecimal refundAmount;
    private String auditRemark;
}
