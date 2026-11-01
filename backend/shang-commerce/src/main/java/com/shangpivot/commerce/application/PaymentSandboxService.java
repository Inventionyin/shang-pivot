package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.OrderItem;
import com.shangpivot.commerce.entity.Payment;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.OrderItemMapper;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.PaymentMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.commerce.mq.OrderPaidEvent;
import com.shangpivot.commerce.mq.OrderPaidEventPublisher;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.common.util.TraceIdUtil;
import com.shangpivot.identity.api.CustomerQueryApi;
import com.shangpivot.identity.api.SystemConfigApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱支付：成功后订单待发货，累加 sold_count。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSandboxService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    private final SpuMapper spuMapper;
    private final CustomerQueryApi customerQueryApi;
    private final SystemConfigApi systemConfigApi;
    private final OrderPaidEventPublisher orderPaidEventPublisher;

    @Transactional
    public Map<String, Object> pay(Long orderId) {
        if (!"true".equalsIgnoreCase(systemConfigApi.getValue("payment.sandbox.enabled", "true"))) {
            throw new BizException(ErrorCode.CONFLICT, "沙箱支付未开启");
        }
        Long customerId = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(customerId)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!"PENDING_PAY".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }

        OffsetDateTime paidAt = OffsetDateTime.now();
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setChannel("SANDBOX");
        payment.setAmount(order.getPayAmount());
        payment.setStatus("PAID");
        payment.setTradeNo("SANDBOX-" + orderId);
        payment.setPaidAt(paidAt);
        paymentMapper.insert(payment);

        order.setStatus("PENDING_SHIP");
        order.setPaidAt(paidAt);
        orderMapper.updateById(order);

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            if (item.getSpuId() == null) {
                continue;
            }
            Spu spu = spuMapper.selectById(item.getSpuId());
            if (spu == null) {
                continue;
            }
            long sold = spu.getSoldCount() == null ? 0L : spu.getSoldCount();
            spu.setSoldCount(sold + (item.getQuantity() == null ? 0 : item.getQuantity()));
            spuMapper.updateById(spu);
        }

        Map<String, Object> m = new HashMap<>();
        m.put("orderId", orderId);
        m.put("status", "PENDING_SHIP");
        m.put("paidAt", paidAt);
        m.put("tradeNo", payment.getTradeNo());
        orderPaidEventPublisher.publish(
                new OrderPaidEvent(orderId, order.getPayAmount(), paidAt, TraceIdUtil.get()));
        log.info("Sandbox payment ok orderId={}", orderId);
        return m;
    }

    public Map<String, Object> status(Long orderId) {
        Long customerId = SecurityContexts.requireUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, orderId)
                .orderByDesc(Payment::getId)
                .last("LIMIT 1"));
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", orderId);
        m.put("orderStatus", order.getStatus());
        m.put("paymentStatus", payment == null ? null : payment.getStatus());
        m.put("tradeNo", payment == null ? null : payment.getTradeNo());
        m.put("paidAt", payment == null ? null : payment.getPaidAt());
        return m;
    }
}
