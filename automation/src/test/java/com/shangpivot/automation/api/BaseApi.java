package com.shangpivot.automation.api;

import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;

/** 领域 API 的公共父类：统一注入 token，不暴露 RestClient 细节。 */
public abstract class BaseApi {

    protected final RestClient client;
    protected final String token;

    protected BaseApi(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    protected ApiResult get(String path) {
        return client.get(path, token);
    }

    protected ApiResult post(String path, Object body) {
        return client.post(path, body, token);
    }

    protected ApiResult put(String path, Object body) {
        return client.put(path, body, token);
    }

    protected ApiResult delete(String path) {
        return client.delete(path, token);
    }
}
