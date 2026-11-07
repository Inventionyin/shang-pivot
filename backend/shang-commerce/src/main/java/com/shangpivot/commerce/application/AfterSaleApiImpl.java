package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.api.AfterSaleApi;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** AfterSaleApi 实现：按 orderNo 定位订单并校验归属，再委托领域服务。 */
@Service
@RequiredArgsConstructor
public class AfterSaleApiImpl implements AfterSaleApi {

    private final OrderMapper orderMapper;
    private final AfterSaleService afterSaleService;

    @Override
    public Long apply(Long customerId, String orderNo, String type, String reason) {
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
        return afterSaleService.apply(customerId, order.getId(), type, reason, null, null);
    }
}
