package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/** 客服工单（区别于售后单 AfterSale）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ticket", schema = "customerservice")
public class CsTicket extends BaseEntity {
    private Long sessionId;
    private Long customerId;
    private String type;
    private String title;
    private String status;
    private Long seatId;
    private String remark;
    private OffsetDateTime slaDueAt;
    private OffsetDateTime resolvedAt;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
}
