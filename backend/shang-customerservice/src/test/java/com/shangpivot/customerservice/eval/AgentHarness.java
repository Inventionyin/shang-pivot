package com.shangpivot.customerservice.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 两阶段流水线的阶段一（教程 §7）：对被测智能体（生产 SSE 接口）跑一次，
 * 收集 answer / cards / sources / tokens / degraded——保证评的是同一次回答。
 * 黑盒 HTTP 驱动：登录评估专用顾客 → 建会话 → 逐轮发送 → 解析 SSE。
 */
public class AgentHarness {

    static {
        // 评测机 DNS 可能抖动：解析结果缓存整场运行（1h），避免中途 UnresolvedAddressException
        java.security.Security.setProperty("networkaddress.cache.ttl", "3600");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }

    public record TurnResult(String answer, List<Map<String, Object>> cards, List<String> sources,
                             boolean degraded, long promptTokens, long completionTokens, String sessionStatus) {
    }

    private static final String BASE = env("EVAL_TARGET_BASE_URL", "http://127.0.0.1:5174");
    /** 评估专用顾客（可多账号轮换，规避 20 条/5 分钟对话限流；逗号分隔）。 */
    private static final String[] PHONES = env("EVAL_CUSTOMER_PHONES",
            env("EVAL_CUSTOMER_PHONE", "13900009999,13900009998")).split(",");
    /** 可选代理（如 http://127.0.0.1:7890）；直连不稳定时设置。 */
    private static final String PROXY = env("EVAL_HTTP_PROXY", "");

    private final HttpClient http = buildClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String token;
    private Long sessionId;

    private static HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        if (!PROXY.isBlank()) {
            URI u = URI.create(PROXY);
            b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(u.getHost(),
                    u.getPort() == -1 ? 80 : u.getPort())));
        }
        return b.build();
    }

    /** 带重试的请求执行（网络超时/瞬断重试 2 次，间隔 5s；POST 重试仅在未建立流之前）。 */
    private HttpResponse<String> sendPlain(HttpRequest req) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException e) {
                last = e;
                Thread.sleep(5_000L * attempt);
            } catch (IOException e) {
                last = e;
                Thread.sleep(3_000L * attempt);
            }
        }
        throw last;
    }

    private static int phoneCursor = 0;

    /** 登录评估专用顾客（沙箱验证码固定 123456，多账号轮换）并创建会话。 */
    public void start() throws IOException, InterruptedException {
        String phone = PHONES[Math.floorMod(phoneCursor++, PHONES.length)].trim();
        String body = objectMapper.writeValueAsString(Map.of(
                "phone", phone, "code", "123456", "clientType", "h5"));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> resp = sendPlain(req);
        JsonNode node = objectMapper.readTree(resp.body());
        if (node.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("评估顾客登录失败: " + resp.body());
        }
        token = node.path("data").path("accessToken").asText();

        HttpRequest create = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/chat/sessions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> resp2 = sendPlain(create);
        JsonNode node2 = objectMapper.readTree(resp2.body());
        if (node2.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("创建会话失败: " + resp2.body());
        }
        sessionId = node2.path("data").path("sessionId").asLong();
        clearCart();
    }

    /** 评估前置清理：清空评估顾客购物车并验证为空（加购叠加会污染数量断言）。 */
    private void clearCart() throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest get = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/api/cart"))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(60)).build();
            HttpResponse<String> resp = sendPlain(get);
            JsonNode items = objectMapper.readTree(resp.body()).path("data").path("items");
            if (items.isEmpty()) {
                return;
            }
            for (JsonNode item : items) {
                HttpRequest del = HttpRequest.newBuilder()
                        .uri(URI.create(BASE + "/api/cart/items/" + item.path("cartItemId").asText()))
                        .header("Authorization", "Bearer " + token)
                        .DELETE().timeout(Duration.ofSeconds(20)).build();
                http.send(del, HttpResponse.BodyHandlers.ofString());
            }
            Thread.sleep(500);
        }
    }

    /** 发送一轮消息并收集 SSE 结果（阶段一：只执行，不评判）。命中对话限流时自动等待一个窗口后重试一次。 */
    public TurnResult send(String content) throws IOException, InterruptedException {
        return send(content, 0);
    }

    private TurnResult send(String content, int retries) throws IOException, InterruptedException {
        if (retries > 2) {
            throw new IllegalStateException("对话限流重试次数超限: " + content);
        }
        String body = objectMapper.writeValueAsString(Map.of("content", content));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/chat/sessions/" + sessionId + "/messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(150))
                .build();
        // 连接阶段同样容忍评测机网络抖动（DNS 瞬断抛 UnresolvedAddressException，属运行时异常）
        HttpResponse<java.io.InputStream> resp = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                break;
            } catch (Exception e) {
                if (attempt >= 3) {
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                Thread.sleep(4_000L * attempt);
            }
        }
        if (resp.statusCode() >= 500 && retries < 2) {
            // 网关/上游瞬断（502/503/504）不应当判定为业务失败；退避后重试同一轮
            resp.body().close();
            Thread.sleep(5_000L * (retries + 1));
            return send(content, retries + 1);
        }
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("发送失败 " + resp.statusCode() + ": " + abbreviate(err));
        }
        // 全局异常处理器返回 HTTP 200 + JSON（如 40000 限流）：不是 SSE 事件流
        String contentType = resp.headers().firstValue("content-type").orElse("");
        if (contentType.contains("application/json")) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            if (err.contains("发送太频繁")) {
                // 固定窗口 5 分钟：等待窗口滑过后重试（最多 2 次）
                Thread.sleep(120_000);
                return send(content, retries + 1);
            }
            throw new IllegalStateException("发送失败（业务错误）: " + abbreviate(err));
        }
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        boolean[] degraded = {false};
        long[] tokens = new long[2];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String event = "";
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line.substring(5).trim());
                switch (event) {
                    case "delta" -> answer.append(node.path("delta").asText(""));
                    case "cards" -> node.path("items").forEach(i ->
                            cards.add(objectMapper.convertValue(i, Map.class)));
                    case "done" -> {
                        node.path("sources").forEach(s -> sources.add(s.isObject()
                                ? String.valueOf(s.path("source")) : s.asText()));
                        degraded[0] = node.path("degraded").asBoolean(false);
                        tokens[0] = node.path("tokens").path("prompt").asLong(0);
                        tokens[1] = node.path("tokens").path("completion").asLong(0);
                    }
                    case "error" -> throw new IllegalStateException("SSE error: " + node);
                    default -> {
                    }
                }
            }
        }
        return new TurnResult(answer.toString().trim(), cards, sources, degraded[0], tokens[0], tokens[1], "");
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 读取购物车终态（参数正确性断言用）：title -> quantity。 */
    public Map<String, Integer> cartState() throws IOException, InterruptedException {
        HttpRequest get = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/cart"))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(60)).build();
        HttpResponse<String> resp = sendPlain(get);
        JsonNode items = objectMapper.readTree(resp.body()).path("data").path("items");
        Map<String, Integer> state = new java.util.LinkedHashMap<>();
        for (JsonNode item : items) {
            state.merge(item.path("title").asText(), item.path("quantity").asInt(0), Integer::sum);
        }
        return state;
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
