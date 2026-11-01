package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** MQ 消费幂等日志：同一订单同一事件只允许一行。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "order_event_log", schema = "commerce")
public class OrderEventLog extends BaseEntity {
    private Long orderId;
    private String eventType;
    private String payload;
}
