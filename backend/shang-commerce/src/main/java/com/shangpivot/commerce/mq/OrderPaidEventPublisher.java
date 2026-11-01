package com.shangpivot.commerce.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单支付事件 → MQ。只在事务提交后发送，避免消费者读到未提交的订单状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidEventPublisher {
    private final ApplicationEventPublisher publisher;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publish(OrderPaidEvent event) {
        publisher.publishEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(OrderPaidEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.orderId() + "-ORDER_PAID");
        payload.put("eventType", "ORDER_PAID");
        payload.put("orderId", event.orderId());
        payload.put("amount", event.amount());
        payload.put("paidAt", event.paidAt());
        payload.put("traceId", event.traceId());
        String body = objectMapper.writeValueAsString(payload);
        rabbitTemplate.convertAndSend(OrderEventConfig.EXCHANGE, OrderEventConfig.ROUTING_KEY, body,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setContentType("application/json");
                    return message;
                });
        log.info("Order paid event sent orderId={} traceId={}", event.orderId(), event.traceId());
    }
}
