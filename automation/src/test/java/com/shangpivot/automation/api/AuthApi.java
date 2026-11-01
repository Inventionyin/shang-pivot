package com.shangpivot.automation.api;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

import java.util.Map;

public final class AuthApi extends BaseApi {

    public AuthApi(RestClient client) {
        super(client, null);
    }

    public ApiResult sendSms(String phone) {
        return post("/api/auth/sms/send", Map.of("phone", phone));
    }

    public ApiResult loginCustomer(String phone, String code) {
        return post("/api/auth/login", Map.of(
                "phone", phone,
                "code", code,
                "clientType", "web"));
    }

    public ApiResult loginCustomer(String phone) {
        return loginCustomer(phone, TestConfig.DEMO_SMS_CODE);
    }

    public ApiResult loginAdmin(String username, String password) {
        return post("/admin/auth/login", Map.of("username", username, "password", password));
    }

    public ApiResult me(String token) {
        return client.get("/api/me", token);
    }

    public ApiResult adminMe(String token) {
        return client.get("/admin/auth/me", token);
    }

    public ApiResult refresh(String refreshToken) {
        return post("/api/auth/refresh", Map.of("refreshToken", refreshToken));
    }
}
