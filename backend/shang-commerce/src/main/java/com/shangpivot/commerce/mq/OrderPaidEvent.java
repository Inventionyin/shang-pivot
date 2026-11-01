package com.shangpivot.commerce.mq;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 支付成功事件（应用内事件，提交后转投 MQ）。 */
public record OrderPaidEvent(Long orderId, BigDecimal amount, OffsetDateTime paidAt, String traceId) {
}
