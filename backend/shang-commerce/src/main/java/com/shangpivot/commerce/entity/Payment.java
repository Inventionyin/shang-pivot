package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "payment", schema = "commerce")
public class Payment extends BaseEntity {
    private Long orderId;
    private String channel;
    private BigDecimal amount;
    private String status;
    private String tradeNo;
    private OffsetDateTime paidAt;
    private String mockParam;
}
