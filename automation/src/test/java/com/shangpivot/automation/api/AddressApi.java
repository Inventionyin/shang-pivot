package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AddressApi extends BaseApi {

    public AddressApi(RestClient client, String token) {
        super(client, token);
    }

    public ApiResult list() {
        return get("/api/addresses");
    }

    public ApiResult create(String name, String phone, String province, String city,
                            String district, String detail, Boolean isDefault) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phone", phone);
        body.put("province", province);
        body.put("city", city);
        body.put("district", district);
        body.put("detail", detail);
        if (isDefault != null) {
            body.put("isDefault", isDefault);
        }
        return post("/api/addresses", body);
    }

    public ApiResult delete(String addressId) {
        return client.delete("/api/addresses/" + addressId, token);
    }

    public ApiResult setDefault(String addressId) {
        return put("/api/addresses/" + addressId + "/default", null);
    }
}
