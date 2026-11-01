package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.OrderItem;
import com.shangpivot.commerce.entity.Payment;
import com.shangpivot.commerce.entity.Shipment;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.entity.StockLog;
import com.shangpivot.commerce.mapper.OrderItemMapper;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.PaymentMapper;
import com.shangpivot.commerce.mapper.ShipmentMapper;
import com.shangpivot.commerce.mapper.StockLogMapper;
import com.shangpivot.commerce.mapper.StockMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台订单用例：分页、详情与发货出库。
 * <p>发货仅允许 PENDING_SHIP → PENDING_RECEIVE，并扣减 physical/locked、写 SHIP 流水。
 * <p>关联表：orders、order_item、shipment、stock、stock_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShipmentMapper shipmentMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final InOutOrderService inOutOrderService;
    private final PaymentMapper paymentMapper;

    public PageResult<Order> page(int page, int size, String orderNo, String status, String customerName,
                                  String phoneTail, String clientType,
                                  OffsetDateTime createdFrom, OffsetDateTime createdTo) {
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                buildQuery(orderNo, status, customerName, phoneTail, clientType, createdFrom, createdTo));
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    private LambdaQueryWrapper<Order> buildQuery(String orderNo, String status, String customerName,
                                                 String phoneTail, String clientType,
                                                 OffsetDateTime createdFrom, OffsetDateTime createdTo) {
        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(orderNo), Order::getOrderNo, orderNo)
                .eq(StringUtils.hasText(status), Order::getStatus, status)
                .like(StringUtils.hasText(customerName), Order::getCustomerName, customerName)
                .eq(StringUtils.hasText(clientType), Order::getClientType, clientType)
                .like(StringUtils.hasText(phoneTail), Order::getCustomerPhoneMask, phoneTail)
                .ge(createdFrom != null, Order::getCreatedAt, createdFrom)
                .le(createdTo != null, Order::getCreatedAt, createdTo)
                .orderByDesc(Order::getId);
        return q;
    }

    /** 导出当前筛选（最多 5000 条）CSV。 */
    public List<Order> listForExport(String orderNo, String status, String customerName,
                                     String phoneTail, String clientType,
                                     OffsetDateTime createdFrom, OffsetDateTime createdTo) {
        return orderMapper.selectList(
                buildQuery(orderNo, status, customerName, phoneTail, clientType, createdFrom, createdTo).last("LIMIT 5000"));
    }

    public Map<String, Object> detail(Long id) {
        Order order = require(id);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id));
        List<Shipment> shipments = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>().eq(Shipment::getOrderId, id));
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, id)
                .orderByDesc(Payment::getId)
                .last("LIMIT 1"));
        Map<String, Object> data = new HashMap<>();
        data.put("order", order);
        data.put("items", items);
        data.put("shipments", shipments);
        data.put("payment", payment);
        if (payment != null) {
            data.put("tradeNo", payment.getTradeNo());
            data.put("paidAt", payment.getPaidAt());
        }
        data.put("clientType", order.getClientType());
        data.put("customerPhoneMask", order.getCustomerPhoneMask());
        return data;
    }

    /**
     * 发货：写物流单、订单改待收货，按明细扣库存并保持 physical≥locked≥0；同事务写 SALE_OUT。
     */
    @Transactional
    public void ship(Long id, ShipReq req) {
        Order order = require(id);
        if (!"PENDING_SHIP".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        if (!StringUtils.hasText(req.getTrackingNo())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请录入运单号");
        }
        order.setStatus("PENDING_RECEIVE");
        order.setShipAt(OffsetDateTime.now());
        orderMapper.updateById(order);

        Shipment shipment = new Shipment();
        shipment.setOrderId(id);
        shipment.setCarrier(StringUtils.hasText(req.getCarrier()) ? req.getCarrier() : "模拟快递");
        shipment.setTrackingNo(req.getTrackingNo().trim());
        shipment.setStatus("SHIPPED");
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> t1 = new HashMap<>();
        t1.put("time", OffsetDateTime.now().toString());
        t1.put("desc", "商家已发货");
        traces.add(t1);
        Map<String, Object> t2 = new HashMap<>();
        t2.put("time", OffsetDateTime.now().plusHours(2).toString());
        t2.put("desc", "运输中（模拟）");
        traces.add(t2);
        shipment.setTraces(traces);
        shipmentMapper.insert(shipment);

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id));
        List<InOutOrderService.ItemReq> ioItems = new ArrayList<>();
        for (OrderItem item : items) {
            if (item.getSkuId() == null) continue;
            Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, item.getSkuId()).last("LIMIT 1"));
            if (stock == null) continue;
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            int physical = stock.getPhysicalStock() == null ? 0 : stock.getPhysicalStock();
            int locked = stock.getLockedStock() == null ? 0 : stock.getLockedStock();
            int nextLocked = Math.max(0, locked - qty);
            int nextPhysical = physical - qty;
            if (nextPhysical < nextLocked || nextPhysical < 0) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "发货库存不足");
            }
            stock.setPhysicalStock(nextPhysical);
            stock.setLockedStock(nextLocked);
            stockMapper.updateById(stock);
            StockLog stockLog = new StockLog();
            stockLog.setSkuId(stock.getSkuId());
            stockLog.setWarehouseId(stock.getWarehouseId());
            stockLog.setChangeQty(-qty);
            stockLog.setType("SHIP");
            stockLog.setReason("订单发货出库 " + order.getOrderNo());
            stockLog.setOperatorId(SecurityContexts.requireUserId());
            stockLogMapper.insert(stockLog);

            InOutOrderService.ItemReq io = new InOutOrderService.ItemReq();
            io.setSkuId(item.getSkuId());
            io.setQty(qty);
            ioItems.add(io);
        }
        if (!ioItems.isEmpty()) {
            inOutOrderService.createSaleOut(order.getOrderNo(), 1L, ioItems, SecurityContexts.requireUserId());
        }
        log.info("Order shipped orderId={} orderNo={} trackingNo={}", id, order.getOrderNo(), req.getTrackingNo());
    }

    private Order require(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    @Data
    @Schema(description = "订单发货请求")
    public static class ShipReq {
        @Schema(description = "承运商", example = "顺丰速运")
        private String carrier;
        @Schema(description = "物流单号", example = "SF1234567890")
        private String trackingNo;
    }
}
