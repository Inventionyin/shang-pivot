package com.shangpivot.ui.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.ui.config.Env;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 只做两件 UI 不适合做的事：① 拿 token 注入 localStorage 以跳过登录（当场景本身不测登录）；
 * ② 场景收尾时用接口清理造出来的数据（购物车行、待支付订单）。
 * <p>
 * 业务断言一律走 UI —— 这条边界不能破，否则 UI 套件会退化成接口套件。
 */
public final class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private ApiClient() {
    }

    public record Tokens(String accessToken, String refreshToken) {
    }

    public static Tokens mallLogin() {
        JsonNode data = post(Env.MALL_URL + "/api/auth/login",
                Map.of("phone", Env.APP_ACCOUNT, "code", Env.APP_SMS_CODE, "clientType", "web"), null);
        return new Tokens(text(data, "accessToken"), text(data, "refreshToken"));
    }

    public static Tokens adminLogin() {
        JsonNode data = post(Env.ADMIN_URL + "/admin/auth/login",
                Map.of("username", Env.ADMIN_ACCOUNT, "password", Env.ADMIN_PASSWORD), null);
        return new Tokens(text(data, "accessToken"), text(data, "refreshToken"));
    }

    /** 取消待支付订单：既释放库存，也是本套件唯一的写入型清理动作。 */
    public static void cancelOrder(String accessToken, String orderId) {
        post(Env.MALL_URL + "/api/orders/" + orderId + "/cancel", Map.of(), accessToken);
    }

    /** 统一响应信封 {code,msg,data,traceId}：调用方一律拿 data，不用自己剥壳。 */
    public static JsonNode get(String url, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return unwrap(url, send(builder.build()));
    }

    public static JsonNode put(String url, Map<String, ?> body, String accessToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (accessToken != null) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            return unwrap(url, send(builder.build()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败：" + e.getMessage(), e);
        }
    }

    public static JsonNode delete(String url, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).DELETE();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return unwrap(url, send(builder.build()));
    }

    private static JsonNode unwrap(String url, JsonNode root) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException(url + " 业务失败：code=" + code + " msg=" + root.path("msg").asText());
        }
        return root.path("data");
    }

    public static JsonNode post(String url, Map<String, ?> body, String accessToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (accessToken != null) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            return unwrap(url, send(builder.build()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败：" + e.getMessage(), e);
        }
    }

    private static JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + "：" + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用接口失败：" + e.getMessage(), e);
        }
    }

    /** 购物车行（含 cartItemId / spuId / skuId / title / quantity / selected）。 */
    public static JsonNode cartItems(String accessToken) {
        return get(Env.MALL_URL + "/api/cart", accessToken).path("items");
    }

    /** 改某行的勾选态：结算携带哪些商品由它决定，测试必须能控制这个前提。 */
    public static void setCartSelected(String cartItemId, boolean selected, String accessToken) {
        put(Env.MALL_URL + "/api/cart/items/" + cartItemId, Map.of("selected", selected), accessToken);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("响应中缺少 " + field + "：" + node);
        }
        return value;
    }
}
