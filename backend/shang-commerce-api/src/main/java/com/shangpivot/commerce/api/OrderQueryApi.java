package com.shangpivot.commerce.api;

import java.util.Collection;
import java.util.Map;

/**
 * Cross-context order query API. Implemented by {@code shang-commerce}.
 */
public interface OrderQueryApi {

    /** 统计指定顾客的订单数。 */
    long countByCustomerId(Long customerId);

    /** 批量统计顾客订单数；缺失键视为 0。 */
    Map<Long, Long> countByCustomerIds(Collection<Long> customerIds);

    /** 按来源端查出有过订单的顾客 ID（用于会员「端」筛选）。 */
    java.util.Set<Long> findCustomerIdsByClientType(String clientType);

    /** 分页查顾客订单（时间倒序）。V3.0 智能客服查单工具。 */
    com.shangpivot.common.result.PageResult<OrderBriefDTO> pageByCustomer(Long customerId, int page, int size);

    /** 查顾客单笔订单轨迹（订单 + 发货/物流沙箱数据）；不属于该顾客或不存在返回 null。 */
    OrderTraceDTO getTrace(Long customerId, String orderNo);
}
