package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.api.OrderBriefDTO;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.commerce.api.OrderTraceDTO;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.Shipment;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.ShipmentMapper;
import com.shangpivot.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderQueryApiImpl implements OrderQueryApi {

    private final OrderMapper orderMapper;
    private final ShipmentMapper shipmentMapper;

    @Override
    public long countByCustomerId(Long customerId) {
        if (customerId == null) {
            return 0L;
        }
        return orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getCustomerId, customerId));
    }

    @Override
    public Map<Long, Long> countByCustomerIds(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<Order>().in(Order::getCustomerId, customerIds))
                .stream()
                .filter(o -> o.getCustomerId() != null)
                .collect(Collectors.groupingBy(Order::getCustomerId, Collectors.counting()));
    }

    @Override
    public Set<Long> findCustomerIdsByClientType(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return Collections.emptySet();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                        .eq(Order::getClientType, clientType)
                        .select(Order::getCustomerId))
                .stream()
                .map(Order::getCustomerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    @Override
    public PageResult<OrderBriefDTO> pageByCustomer(Long customerId, int page, int size) {
        if (customerId == null) {
            return PageResult.of(0, List.of());
        }
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Order>()
                .eq(Order::getCustomerId, customerId)
                .orderByDesc(Order::getId));
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toBrief).toList());
    }

    @Override
    public OrderTraceDTO getTrace(Long customerId, String orderNo) {
        if (customerId == null || orderNo == null || orderNo.isBlank()) {
            return null;
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getCustomerId, customerId)
                .eq(Order::getOrderNo, orderNo)
                .last("LIMIT 1"));
        if (order == null) {
            return null;
        }
        Shipment shipment = shipmentMapper.selectOne(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getOrderId, order.getId())
                .orderByDesc(Shipment::getId)
                .last("LIMIT 1"));
        return new OrderTraceDTO(
                order.getOrderNo(),
                order.getStatus(),
                order.getPayAmount() == null ? null : order.getPayAmount().toPlainString(),
                order.getShipAt(),
                shipment == null ? null : shipment.getCarrier(),
                shipment == null ? null : shipment.getTrackingNo(),
                shipment == null ? null : shipment.getStatus(),
                shipment == null || shipment.getTraces() == null ? Collections.emptyList() : shipment.getTraces()
        );
    }

    private OrderBriefDTO toBrief(Order o) {
        return new OrderBriefDTO(
                o.getOrderNo(),
                o.getStatus(),
                o.getPayAmount(),
                o.getCreatedAt(),
                o.getPaidAt(),
                o.getShipAt(),
                o.getReceivedAt(),
                o.getCloseReason()
        );
    }
}
