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

    public record TurnResult(String answer, List<Map<String, Object>> cards, List<String> sources,
                             boolean degraded, long promptTokens, long completionTokens, String sessionStatus) {
    }

    private static final String BASE = env("EVAL_TARGET_BASE_URL", "http://127.0.0.1:5174");
    private static final String PHONE = env("EVAL_CUSTOMER_PHONE", "13900009999");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String token;
    private Long sessionId;

    /** 登录评估专用顾客（沙箱验证码固定 123456）并创建会话。 */
    public void start() throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(Map.of(
                "phone", PHONE, "code", "123456", "clientType", "h5"));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
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
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> resp2 = http.send(create, HttpResponse.BodyHandlers.ofString());
        JsonNode node2 = objectMapper.readTree(resp2.body());
        if (node2.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("创建会话失败: " + resp2.body());
        }
        sessionId = node2.path("data").path("sessionId").asLong();
        clearCart();
    }

    /** 评估前置清理：清空评估顾客购物车，保证用例可重复（加购叠加会污染数量断言）。 */
    private void clearCart() throws IOException, InterruptedException {
        HttpRequest get = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/cart"))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(20)).build();
        HttpResponse<String> resp = http.send(get, HttpResponse.BodyHandlers.ofString());
        JsonNode items = objectMapper.readTree(resp.body()).path("data").path("items");
        for (JsonNode item : items) {
            HttpRequest del = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/api/cart/items/" + item.path("cartItemId").asText()))
                    .header("Authorization", "Bearer " + token)
                    .DELETE().timeout(Duration.ofSeconds(20)).build();
            http.send(del, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 发送一轮消息并收集 SSE 结果（阶段一：只执行，不评判）。 */
    public TurnResult send(String content) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(Map.of("content", content));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/api/chat/sessions/" + sessionId + "/messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(150))
                .build();
        HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("发送失败 " + resp.statusCode() + ": " + abbreviate(err));
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

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
