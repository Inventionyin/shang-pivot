package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CartApi extends BaseApi {

    public CartApi(RestClient client, String token) {
        super(client, token);
    }

    public ApiResult getCart() {
        return get("/api/cart");
    }

    public ApiResult add(String skuId, int quantity) {
        return post("/api/cart/items", Map.of("skuId", skuId, "quantity", quantity));
    }
    public ApiResult addRaw(String skuId, Object quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skuId", skuId);
        body.put("quantity", quantity);
        return post("/api/cart/items", body);
    }

    public ApiResult updateQuantity(String cartItemId, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quantity", quantity);
        return put("/api/cart/items/" + cartItemId, body);
    }

    public ApiResult updateSelected(String cartItemId, boolean selected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selected", selected);
        return put("/api/cart/items/" + cartItemId, body);
    }

    public ApiResult remove(String cartItemId) {
        return delete("/api/cart/items/" + cartItemId);
    }

    public ApiResult clearInvalid() {
        return post("/api/cart/clear-invalid", null);
    }
}
