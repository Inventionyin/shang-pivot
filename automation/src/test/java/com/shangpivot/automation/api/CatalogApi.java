package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

public final class CatalogApi extends BaseApi {

    public CatalogApi(RestClient client) {
        super(client, null);
    }

    public ApiResult home(int feedSize) {
        return get("/api/home?feedSize=" + feedSize);
    }

    public ApiResult categories() {
        return get("/api/categories");
    }

    public ApiResult products(int page, int size, String sort) {
        return get("/api/products?page=%d&size=%d&sort=%s".formatted(page, size, sort));
    }

    public ApiResult product(String spuId) {
        return get("/api/products/" + spuId);
    }

    public ApiResult search(String keyword, int page, int size) {
        return get("/api/search?q=%s&page=%d&size=%d".formatted(encode(keyword), page, size));
    }

    public ApiResult reviews(String spuId, int page, int size) {
        return get("/api/products/%s/reviews?page=%d&size=%d".formatted(spuId, page, size));
    }

    public ApiResult siteBrand() {
        return get("/api/site/brand");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
