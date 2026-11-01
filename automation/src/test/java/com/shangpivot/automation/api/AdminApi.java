package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AdminApi extends BaseApi {

    public AdminApi(RestClient client, String token) {
        super(client, token);
    }

    public ApiResult stats() {
        return get("/admin/dashboard/stats");
    }

    public ApiResult products(int page, int size, String name) {
        String query = name == null || name.isBlank()
                ? "/admin/products?page=%d&size=%d".formatted(page, size)
                : "/admin/products?page=%d&size=%d&name=%s".formatted(page, size, encode(name));
        return get(query);
    }

    public ApiResult product(String productId) {
        return get("/admin/products/" + productId);
    }

    public ApiResult orders(int page, int size, String status) {
        String query = status == null || status.isBlank()
                ? "/admin/orders?page=%d&size=%d".formatted(page, size)
                : "/admin/orders?page=%d&size=%d&status=%s".formatted(page, size, status);
        return get(query);
    }

    public ApiResult orderDetail(String orderId) {
        return get("/admin/orders/" + orderId);
    }

    public ApiResult ship(String orderId, String trackingNo, String carrier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trackingNo", trackingNo);
        if (carrier != null) {
            body.put("carrier", carrier);
        }
        return post("/admin/orders/" + orderId + "/ship", body);
    }

    public ApiResult afterSales(int page, int size) {
        return get("/admin/after-sales?page=%d&size=%d".formatted(page, size));
    }

    public ApiResult auditAfterSale(String id, boolean approved, String remark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approved", approved);
        if (remark != null) {
            body.put("remark", remark);
        }
        return post("/admin/after-sales/" + id + "/audit", body);
    }

    public ApiResult customers(int page, int size) {
        return get("/admin/customers?page=%d&size=%d".formatted(page, size));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
