package com.shangpivot.commerce.api;

/**
 * Cross-context order command API for the V3.1 agent runtime.
 * customerId is explicit because agent execution has no HTTP security context.
 */
public interface OrderCommandApi {

    /** 取消待支付订单；已关闭保持幂等成功。 */
    void cancel(Long customerId, String orderNo);

    /** 确认收货（仅待收货）。 */
    void confirmReceive(Long customerId, String orderNo);
}
