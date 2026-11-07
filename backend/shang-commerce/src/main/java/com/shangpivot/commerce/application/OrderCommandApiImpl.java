package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.api.OrderCommandApi;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** OrderCommandApi 实现：customerId 显式入参，订单归属校验后委托领域服务。 */
@Service
@RequiredArgsConstructor
public class OrderCommandApiImpl implements OrderCommandApi {

    private final OrderMapper orderMapper;
    private final OrderSubmitService orderSubmitService;

    @Override
    public void cancel(Long customerId, String orderNo) {
        Order order = requireOwned(customerId, orderNo);
        orderSubmitService.cancel(customerId, order.getId());
    }

    @Override
    public void confirmReceive(Long customerId, String orderNo) {
        Order order = requireOwned(customerId, orderNo);
        orderSubmitService.confirmReceive(customerId, order.getId());
    }

    private Order requireOwned(Long customerId, String orderNo) {
        if (customerId == null || !StringUtils.hasText(orderNo)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单号必填");
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo.trim())
                .eq(Order::getCustomerId, customerId)
                .last("LIMIT 1"));
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }
}
