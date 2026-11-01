package com.shangpivot.automation.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.automation.core.allure.AllureSupport;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * REST 传输层：超时、鉴权头、统一响应解析、Allure 请求/响应附件。
 * 业务断言不在这里，由各领域 API 与步骤负责。
 */
public final class RestClient {

    private static final Logger log = LoggerFactory.getLogger(RestClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final RequestSpecification spec;

    public RestClient(String baseUrl) {
        this.baseUrl = baseUrl;
        RestAssuredConfig config = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 5_000)
                        .setParam("http.socket.timeout", 30_000));
        this.spec = given()
                .config(config)
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ApiResult get(String path, String token) {
        return execute("GET", path, null, token);
    }

    public ApiResult post(String path, Object body, String token) {
        return execute("POST", path, body, token);
    }
    public ApiResult postRaw(String path, String rawBody, String token, String contentType) {
        RequestSpecification request = given().spec(spec).contentType(contentType);
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        request.body(rawBody);
        long started = System.nanoTime();
        Response response = request.post(path);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        String raw = response.asString();
        JsonNode root = null;
        if (raw != null && raw.stripLeading().startsWith("{")) {
            try {
                root = MAPPER.readTree(raw);
            } catch (Exception ignored) {
            }
        }
        ApiResult result = new ApiResult(response.statusCode(), root);
        AllureSupport.attachExchange("POST", baseUrl + path, rawBody,
                response.statusCode(), raw, elapsedMs);
        log.info("[api] POST {}{} -> HTTP {} code={} {}ms",
                baseUrl, path, response.statusCode(), result.code(), elapsedMs);
        return result;
    }

    public ApiResult put(String path, Object body, String token) {
        return execute("PUT", path, body, token);
    }

    public ApiResult delete(String path, String token) {
        return execute("DELETE", path, null, token);
    }

    private ApiResult execute(String method, String path, Object body, String token) {
        RequestSpecification request = given().spec(spec);
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        String requestBody = serialize(body);
        if (requestBody != null) {
            request.body(requestBody);
        }

        long started = System.nanoTime();
        Response response;
        try {
            response = switch (method) {
                case "GET" -> request.get(path);
                case "POST" -> request.post(path);
                case "PUT" -> request.put(path);
                case "DELETE" -> request.delete(path);
                default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
            };
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            AllureSupport.attachExchange(method, baseUrl + path, requestBody, -1,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
            throw e;
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        String raw = response.asString();
        JsonNode root = null;
        if (raw != null && !raw.isBlank() && raw.stripLeading().startsWith("{")) {
            try {
                root = MAPPER.readTree(raw);
            } catch (Exception ignored) {
                // 非 JSON 或截断响应，保留原文即可
            }
        }
        ApiResult result = new ApiResult(response.statusCode(), root);
        AllureSupport.attachExchange(method, baseUrl + path, requestBody,
                response.statusCode(), raw, elapsedMs);
        log.info("[api] {} {}{} -> HTTP {} code={} {}ms",
                method, baseUrl, path, response.statusCode(), result.code(), elapsedMs);
        return result;
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(body instanceof Map<?, ?> ? body : body);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体序列化失败: " + body, e);
        }
    }
}
