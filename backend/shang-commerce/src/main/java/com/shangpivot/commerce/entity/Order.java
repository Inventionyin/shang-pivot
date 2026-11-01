package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "\"order\"", schema = "commerce", autoResultMap = true)
public class Order extends BaseEntity {
    private String orderNo;
    private Long customerId;
    private String customerName;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal freight;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> addressSnapshot;
    private String remark;
    private OffsetDateTime paidAt;
    private OffsetDateTime shipAt;
    private OffsetDateTime receivedAt;
    private String closeReason;
    private String clientType;
    private String customerPhoneMask;
    private String idempotentKey;
}
