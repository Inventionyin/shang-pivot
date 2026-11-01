package com.shangpivot.commerce.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 顾客订单摘要（智能客服查单用）。
 */
public record OrderBriefDTO(
        String orderNo,
        String status,
        BigDecimal payAmount,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime shipAt,
        OffsetDateTime receivedAt,
        String closeReason
) {
}
