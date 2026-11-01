package com.shangpivot.commerce.mq;

import com.shangpivot.commerce.entity.OrderEventLog;
import com.shangpivot.commerce.mapper.OrderEventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单事件消费者：按 (orderId, eventType) 幂等落库。
 *
 * <p>重复投递吞掉并确认；格式错误或数据库不可用则抛出，由监听容器重试，
 * 超过 3 次进入死信队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {
    private final OrderEventLogMapper mapper;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = OrderEventConfig.QUEUE)
    public void handle(String body) {
        JsonNode node = objectMapper.readTree(body);
        // 全局 Jackson 配置把 Long 序列化为字符串，这里统一按文本解析
        String orderIdText = node.path("orderId").asString("");
        long orderId;
        try {
            orderId = Long.parseLong(orderIdText);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("orderId 缺失或非法: " + body);
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId 缺失或非法: " + body);
        }
        String eventType = node.path("eventType").asString("");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 缺失: " + body);
        }

        OrderEventLog row = new OrderEventLog();
        row.setOrderId(orderId);
        row.setEventType(eventType);
        row.setPayload(body);
        String traceId = node.path("traceId").asString("");
        if (!traceId.isBlank()) {
            row.setTraceId(traceId);
        }
        try {
            mapper.insert(row);
            log.info("Order event persisted orderId={} type={}", orderId, eventType);
        } catch (DuplicateKeyException duplicate) {
            log.info("Order event duplicated, ignored orderId={} type={}", orderId, eventType);
        } catch (DataIntegrityViolationException violation) {
            if (String.valueOf(violation.getMessage()).contains("uk_order_event")) {
                log.info("Order event duplicated, ignored orderId={} type={}", orderId, eventType);
            } else {
                throw violation;
            }
        }
    }
}
