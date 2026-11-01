package com.shangpivot.commerce.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单轨迹（订单 + 发货信息 + 沙箱物流轨迹）。
 */
public record OrderTraceDTO(
        String orderNo,
        String status,
        String payAmount,
        OffsetDateTime shipAt,
        String carrier,
        String trackingNo,
        String shipmentStatus,
        List<Map<String, Object>> traces
) {
}
