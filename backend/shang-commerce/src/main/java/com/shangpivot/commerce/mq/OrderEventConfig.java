package com.shangpivot.commerce.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单事件拓扑：主队列绑定死信交换机，消费失败重试 3 次后进入 DLQ。
 */
@Configuration
public class OrderEventConfig {
    public static final String EXCHANGE = "commerce.order.event";
    public static final String ROUTING_KEY = "order.paid";
    public static final String QUEUE = "commerce.order.event.queue";

    public static final String DLX = "commerce.order.event.dlx";
    public static final String DLQ = "commerce.order.event.dlq";
    public static final String DLQ_ROUTING_KEY = "order.paid.dead";

    @Bean
    public DirectExchange orderEventExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue orderEventQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderEventBinding() {
        return BindingBuilder.bind(orderEventQueue()).to(orderEventExchange()).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderEventDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue orderEventDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding orderEventDeadLetterBinding() {
        return BindingBuilder.bind(orderEventDeadLetterQueue())
                .to(orderEventDeadLetterExchange())
                .with(DLQ_ROUTING_KEY);
    }
}
