package com.shangpivot.commerce.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.application.OrderSubmitService;
import com.shangpivot.commerce.application.ProductService;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.identity.api.SystemConfigApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommerceJobs {

    private final OrderMapper orderMapper;
    private final OrderSubmitService orderSubmitService;
    private final SpuMapper spuMapper;
    private final ProductService productService;
    private final SystemConfigApi systemConfigApi;

    @Scheduled(cron = "0 * * * * *")
    public void payTimeoutClose() {
        int minutes = systemConfigApi.getInt("order.pay-timeout-minutes", 15);
        OffsetDateTime deadline = OffsetDateTime.now().minusMinutes(minutes);
        List<Order> list = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, "PENDING_PAY")
                .lt(Order::getCreatedAt, deadline)
                .last("LIMIT 100"));
        for (Order order : list) {
            try {
                orderSubmitService.closeAndUnlock(order, "支付超时自动关闭");
            } catch (Exception e) {
                log.warn("PayTimeoutClose failed orderId={}: {}", order.getId(), e.getMessage());
            }
        }
    }

    /** 定时上架：到点后走与手动上架相同的 V1 校验（主图 / 可售 SKU / 价格）。 */
    @Scheduled(cron = "0 * * * * *")
    public void scheduleOnShelf() {
        List<Spu> list = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .isNotNull(Spu::getScheduleOnAt)
                .le(Spu::getScheduleOnAt, OffsetDateTime.now())
                .ne(Spu::getStatus, "ON")
                .last("LIMIT 50"));
        for (Spu spu : list) {
            try {
                productService.changeStatus(spu.getId(), "ON");
                log.info("ScheduleOnShelf ok spuId={}", spu.getId());
            } catch (Exception e) {
                log.warn("ScheduleOnShelf failed spuId={}: {}", spu.getId(), e.getMessage());
            }
        }
    }
}
