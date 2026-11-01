package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.Map;

public final class PaymentApi extends BaseApi {

    public PaymentApi(RestClient client, String token) {
        super(client, token);
    }

    public ApiResult sandboxPay(String orderId) {
        return post("/api/payment/sandbox", Map.of("orderId", orderId));
    }

    public ApiResult status(String orderId) {
        return get("/api/payment/" + orderId + "/status");
    }
}
