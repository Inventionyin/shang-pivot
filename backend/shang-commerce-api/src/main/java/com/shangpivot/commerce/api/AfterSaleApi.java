package com.shangpivot.commerce.api;

/**
 * Cross-context after-sale command API for the V3.1 agent runtime.
 */
public interface AfterSaleApi {

    /**
     * 以顾客身份发起售后。
     *
     * @param customerId 顾客 ID（后端注入，非模型参数）
     * @param orderNo    订单号
     * @param type       REFUND / RETURN_REFUND / EXCHANGE
     * @param reason     原因（≤512 字）
     * @return 售后单 ID
     */
    Long apply(Long customerId, String orderNo, String type, String reason);
}
