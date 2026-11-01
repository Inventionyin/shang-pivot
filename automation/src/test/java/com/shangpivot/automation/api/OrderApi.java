package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderApi extends BaseApi {

    public OrderApi(RestClient client, String token) {
        super(client, token);
    }

    public ApiResult submitFromCart(String addressId, List<String> cartItemIds, String idempotentKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", addressId);
        body.put("cartItemIds", cartItemIds);
        body.put("idempotentKey", idempotentKey);
        return post("/api/orders", body);
    }

    public ApiResult submitDirect(String addressId, String skuId, int quantity, String idempotentKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", addressId);
        body.put("skuId", skuId);
        body.put("quantity", quantity);
        body.put("idempotentKey", idempotentKey);
        return post("/api/orders", body);
    }

    public ApiResult submitDirectWithExtras(String addressId, String skuId, int quantity,
                                            String idempotentKey, Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", addressId);
        body.put("skuId", skuId);
        body.put("quantity", quantity);
        body.put("idempotentKey", idempotentKey);
        if (extras != null) {
            body.putAll(extras);
        }
        return post("/api/orders", body);
    }

    public ApiResult list(String status, int page, int size) {
        String query = status == null || status.isBlank()
                ? "/api/orders?page=%d&size=%d".formatted(page, size)
                : "/api/orders?status=%s&page=%d&size=%d".formatted(status, page, size);
        return get(query);
    }

    public ApiResult detail(String orderId) {
        return get("/api/orders/" + orderId);
    }

    public ApiResult cancel(String orderId) {
        return post("/api/orders/" + orderId + "/cancel", null);
    }

    public ApiResult confirm(String orderId) {
        return post("/api/orders/" + orderId + "/confirm", null);
    }

    public ApiResult logistics(String orderId) {
        return get("/api/orders/" + orderId + "/logistics");
    }
}
