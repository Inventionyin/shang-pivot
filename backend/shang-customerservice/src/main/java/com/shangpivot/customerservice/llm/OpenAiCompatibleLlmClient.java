package com.shangpivot.customerservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

/**
 * OpenAI 兼容网关实现（JDK HttpClient + SSE 行解析）。
 * 兼容点：思考型模型的 delta.reasoning_content 与 delta.content 分轨；
 * 忽略注释行（\": heartbeat\"）与 [DONE]。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmSettingsResolver settingsResolver;
    private final ObjectMapper objectMapper;

    private volatile HttpClient httpClient;

    private HttpClient http() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return httpClient;
    }

    @Override
    public boolean available() {
        return settingsResolver.resolve().chatReady();
    }

    @Override
    public StreamResult streamChat(String systemPrompt, List<Map<String, String>> messages, StreamListener listener) {
        LlmSettings s = settingsResolver.resolve();
        if (!s.chatReady()) {
            throw new LlmException("LLM 未配置（llm.base-url/api-key/model）");
        }
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        payloadMessages.add(Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt));
        payloadMessages.addAll(messages);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", s.getModel());
        payload.put("messages", payloadMessages);
        payload.put("temperature", s.getTemperature());
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));
        if (s.getReasoningEffort() != null && !s.getReasoningEffort().isBlank()) {
            payload.put("reasoning_effort", s.getReasoningEffort());
        }

        HttpRequest request = buildRequest(s, payload);
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        long[] usage = new long[2];
        try {
            HttpResponse<java.io.InputStream> resp = http().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("LLM 网关返回 " + resp.statusCode() + ": " + abbreviate(body));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith(":")) {
                        continue; // 心跳/注释
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node = objectMapper.readTree(data);
                    // 网关在最后一个 chunk 携带 usage（include_usage）
                    JsonNode usageNode = node.path("usage");
                    if (usageNode.isObject()) {
                        usage[0] = usageNode.path("prompt_tokens").asLong(0);
                        usage[1] = usageNode.path("completion_tokens").asLong(0);
                    }
                    JsonNode delta = node.path("choices").path(0).path("delta");
                    String r = delta.path("reasoning_content").asText(null);
                    if (r != null && !r.isEmpty()) {
                        reasoning.append(r);
                        if (listener != null) {
                            listener.onReasoning(r);
                        }
                    }
                    String c = delta.path("content").asText(null);
                    if (c != null && !c.isEmpty()) {
                        content.append(c);
                        if (listener != null) {
                            listener.onContent(c);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new LlmException("LLM 流式请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 流式请求被中断", e);
        }
        return new StreamResult(reasoning.toString(), content.toString(), usage[0], usage[1]);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        LlmSettings s = settingsResolver.resolve();
        if (!s.chatReady()) {
            throw new LlmException("LLM 未配置");
        }
        Map<String, Object> payload = Map.of(
                "model", s.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false
        );
        HttpRequest request = buildRequest(s, payload);
        try {
            HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("LLM 网关返回 " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return node.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            throw new LlmException("LLM 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 请求被中断", e);
        }
    }

    @Override
    public List<String> listModels() {
        LlmSettings s = settingsResolver.resolve();
        if (!s.chatReady()) {
            throw new LlmException("LLM 未配置");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(s.getBaseUrl()) + "/models"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + s.getApiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("模型列表返回 " + resp.statusCode());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            List<String> ids = new ArrayList<>();
            node.path("data").forEach(m -> ids.add(m.path("id").asText()));
            return ids;
        } catch (IOException e) {
            throw new LlmException("模型列表请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("模型列表请求被中断", e);
        }
    }

    private HttpRequest buildRequest(LlmSettings s, Map<String, Object> payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new LlmException("payload 序列化失败", e);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(s.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + s.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
