package com.shangpivot.customerservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 客户端（可插拔，见 ADR-0006）。
 * 未配置 embedding（embeddingReady=false）时调用方必须走词法检索。
 */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final LlmSettingsResolver settingsResolver;
    private final ObjectMapper objectMapper;

    private volatile HttpClient httpClient;

    public boolean ready() {
        return settingsResolver.resolve().embeddingReady();
    }

    /** 单文本向量化，返回 pgvector 字面量 '[0.1,0.2,...]'。 */
    public String embed(String text) {
        List<Float> v = embedBatch(List.of(text)).get(0);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v.get(i));
        }
        return sb.append(']').toString();
    }

    /** 批量向量化。 */
    @SuppressWarnings("unchecked")
    public List<List<Float>> embedBatch(List<String> texts) {
        LlmSettings s = settingsResolver.resolve();
        if (!s.embeddingReady()) {
            throw new LlmClient.LlmException("Embedding 未配置（llm.embedding.*）");
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "model", s.getEmbeddingModel(),
                    "input", texts
            ));
        } catch (IOException e) {
            throw new LlmClient.LlmException("embedding payload 序列化失败", e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(s.getEmbeddingBaseUrl()) + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + s.getEmbeddingApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmClient.LlmException("Embedding 返回 " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode node = objectMapper.readTree(resp.body());
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            node.path("data").forEach(d -> rows.add(Map.of(
                    "index", d.path("index").asInt(),
                    "embedding", objectMapper.convertValue(d.path("embedding"), List.class)
            )));
            rows.sort(java.util.Comparator.comparingInt(r -> (int) r.get("index")));
            return rows.stream().map(r -> (List<Float>) r.get("embedding")).toList();
        } catch (IOException e) {
            throw new LlmClient.LlmException("Embedding 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClient.LlmException("Embedding 请求被中断", e);
        }
    }

    private HttpClient http() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
            }
        }
        return httpClient;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
