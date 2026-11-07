package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.*;
import com.shangpivot.commerce.mapper.*;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.LoginUser;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.api.CustomerQueryApi;
import com.shangpivot.identity.api.SystemConfigApi;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C 端提交订单：锁库存 + 写单 + 清购物车，同一本地事务；幂等键防重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSubmitService {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final CartItemMapper cartItemMapper;
    private final AddressMapper addressMapper;
    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final CustomerQueryApi customerQueryApi;
    private final SystemConfigApi systemConfigApi;

    @Transactional
    public Map<String, Object> submit(SubmitReq req) {
        Long customerId = requireActiveCustomer();
        if (!StringUtils.hasText(req.getIdempotentKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "idempotentKey 必填");
        }
        Order existed = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getCustomerId, customerId)
                .eq(Order::getIdempotentKey, req.getIdempotentKey()));
        if (existed != null) {
            return toSubmitVo(existed);
        }
        Address address = addressMapper.selectById(req.getAddressId());
        if (address == null || !customerId.equals(address.getCustomerId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "收货地址无效");
        }

        List<Line> lines = resolveLines(customerId, req);
        if (lines.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有可下单商品");
        }

        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (Line line : lines) {
            lockStock(line.sku.getId(), line.quantity);
            goodsAmount = goodsAmount.add(line.sku.getPrice().multiply(BigDecimal.valueOf(line.quantity)));
        }

        BigDecimal freeThreshold = new BigDecimal(systemConfigApi.getValue("freight.free-threshold", "99"));
        BigDecimal defaultFreight = new BigDecimal(systemConfigApi.getValue("freight.default-amount", "8"));
        BigDecimal freight = goodsAmount.compareTo(freeThreshold) >= 0 ? BigDecimal.ZERO : defaultFreight;
        BigDecimal payAmount = goodsAmount.add(freight);
        int timeoutMin = systemConfigApi.getInt("order.pay-timeout-minutes", 15);

        LoginUser user = SecurityContexts.get();
        Order order = new Order();
        order.setOrderNo(nextOrderNo());
        order.setCustomerId(customerId);
        order.setCustomerName(address.getName());
        order.setStatus("PENDING_PAY");
        order.setTotalAmount(goodsAmount);
        order.setPayAmount(payAmount);
        order.setFreight(freight);
        order.setClientType(user == null ? null : user.getClientType());
        order.setCustomerPhoneMask(customerQueryApi.maskedPhone(customerId));
        order.setIdempotentKey(req.getIdempotentKey());
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", address.getName());
        snapshot.put("phone", address.getPhone());
        snapshot.put("province", address.getProvince());
        snapshot.put("city", address.getCity());
        snapshot.put("district", address.getDistrict());
        snapshot.put("detail", address.getDetail());
        order.setAddressSnapshot(snapshot);
        orderMapper.insert(order);

        for (Line line : lines) {
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setSkuId(line.sku.getId());
            oi.setSpuId(line.sku.getSpuId());
            oi.setTitle(line.sku.getTitle());
            oi.setPic(line.sku.getPic());
            oi.setPrice(line.sku.getPrice());
            oi.setQuantity(line.quantity);
            oi.setSpecs(line.sku.getSpecs());
            oi.setSubtotal(line.sku.getPrice().multiply(BigDecimal.valueOf(line.quantity)));
            orderItemMapper.insert(oi);
            if (line.cartItemId != null) {
                cartItemMapper.deleteById(line.cartItemId);
            }
        }

        OffsetDateTime expireAt = OffsetDateTime.now().plusMinutes(timeoutMin);
        Map<String, Object> vo = toSubmitVo(order);
        vo.put("expireAt", expireAt);
        log.info("Order submitted orderId={} customerId={} payAmount={}", order.getId(), customerId, payAmount);
        return vo;
    }

    @Transactional
    public void cancel(Long orderId) {
        cancel(requireActiveCustomer(), orderId);
    }

    /** 跨上下文（Agent 工具）入口：customerId 显式传入，仍做归属校验。 */
    @Transactional
    public void cancel(Long customerId, Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        // 需求 MALL-08「仅 PENDING_PAY 可取消」：非待支付订单必须显式拒绝，
        // 不能复用 closeAndUnlock 的静默幂等（否则用户收到成功响应但订单状态未变，属误导）。
        // 例外：订单已 CLOSED（重复取消）保持幂等成功——库存不会二次释放，重复点击不应报错。
        if ("CLOSED".equals(order.getStatus())) {
            return;
        }
        if (!"PENDING_PAY".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        closeAndUnlock(order, "用户取消");
    }

    /** Job / 取消共用：幂等关闭待付款并释锁。 */
    @Transactional
    public void closeAndUnlock(Order order, String reason) {
        if (!"PENDING_PAY".equals(order.getStatus())) {
            return;
        }
        order.setStatus("CLOSED");
        order.setCloseReason(reason);
        orderMapper.updateById(order);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem item : items) {
            unlockStock(item.getSkuId(), item.getQuantity());
        }
        log.info("Order closed orderId={} reason={}", order.getId(), reason);
    }

    @Transactional
    public void confirmReceive(Long orderId) {
        confirmReceive(requireActiveCustomer(), orderId);
    }

    /** 跨上下文（Agent 工具）入口：customerId 显式传入，仍做归属校验。 */
    @Transactional
    public void confirmReceive(Long customerId, Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!"PENDING_RECEIVE".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID);
        }
        order.setStatus("COMPLETED");
        order.setReceivedAt(OffsetDateTime.now());
        orderMapper.updateById(order);
    }

    private List<Line> resolveLines(Long customerId, SubmitReq req) {
        List<Line> lines = new ArrayList<>();
        if (req.getCartItemIds() != null && !req.getCartItemIds().isEmpty()) {
            Cart cart = cartMapper.selectOne(new LambdaQueryWrapper<Cart>().eq(Cart::getCustomerId, customerId));
            if (cart == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "购物车为空");
            }
            for (Long cartItemId : req.getCartItemIds()) {
                CartItem ci = cartItemMapper.selectById(cartItemId);
                if (ci == null || !cart.getId().equals(ci.getCartId())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "购物车项无效");
                }
                Sku sku = requireOnSaleSku(ci.getSkuId());
                lines.add(new Line(sku, ci.getQuantity(), ci.getId()));
            }
        } else if (req.getSkuId() != null && req.getQuantity() != null) {
            Sku sku = requireOnSaleSku(req.getSkuId());
            if (req.getQuantity() < 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "数量须 ≥ 1");
            }
            lines.add(new Line(sku, req.getQuantity(), null));
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择购物车项或立即购买 SKU");
        }
        return lines;
    }

    private void lockStock(Long skuId, int qty) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, skuId).last("LIMIT 1 FOR UPDATE"));
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT);
        }
        int physical = stock.getPhysicalStock() == null ? 0 : stock.getPhysicalStock();
        int locked = stock.getLockedStock() == null ? 0 : stock.getLockedStock();
        if (physical - locked < qty) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT);
        }
        stock.setLockedStock(locked + qty);
        stockMapper.updateById(stock);
        StockLog logRow = new StockLog();
        logRow.setSkuId(skuId);
        logRow.setWarehouseId(stock.getWarehouseId());
        logRow.setChangeQty(qty);
        logRow.setType("LOCK");
        logRow.setReason("下单锁库存");
        logRow.setOperatorId(SecurityContexts.requireUserId());
        stockLogMapper.insert(logRow);
    }

    private void unlockStock(Long skuId, int qty) {
        if (skuId == null || qty <= 0) {
            return;
        }
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, skuId).last("LIMIT 1 FOR UPDATE"));
        if (stock == null) {
            return;
        }
        int locked = stock.getLockedStock() == null ? 0 : stock.getLockedStock();
        stock.setLockedStock(Math.max(0, locked - qty));
        stockMapper.updateById(stock);
        StockLog logRow = new StockLog();
        logRow.setSkuId(skuId);
        logRow.setWarehouseId(stock.getWarehouseId());
        logRow.setChangeQty(-qty);
        logRow.setType("UNLOCK");
        logRow.setReason("关单释锁");
        stockLogMapper.insert(logRow);
    }

    private Sku requireOnSaleSku(Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null || !"ENABLED".equals(sku.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        Spu spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || !"ON".equals(spu.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        return sku;
    }

    private Long requireActiveCustomer() {
        Long id = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(id)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        return id;
    }

    private Map<String, Object> toSubmitVo(Order order) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", order.getId());
        m.put("orderNo", order.getOrderNo());
        m.put("status", order.getStatus());
        m.put("goodsAmount", order.getTotalAmount());
        m.put("freight", order.getFreight());
        m.put("payAmount", order.getPayAmount());
        return m;
    }

    private String nextOrderNo() {
        return "O" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", SEQ.getAndIncrement() % 10000);
    }

    private record Line(Sku sku, int quantity, Long cartItemId) {}

    @Data
    public static class SubmitReq {
        private Long addressId;
        private List<Long> cartItemIds;
        private Long skuId;
        private Integer quantity;
        private String idempotentKey;
    }
}
