package com.shangpivot.customerservice.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GEval-lite 裁判（教程 §6 答案正确性思路）：按用例给的 evaluationSteps（2~4 条）打分并输出判词。
 * 评判模型与执行模型分离可配；强制 reasoning_effort=none 保证判词干净、响应快。
 * 未配置 EVAL_LLM_API_KEY 时裁判整体跳过（只跑确定性断言，成本阀）。
 */
public class LlmJudge {

    public record Verdict(double score, String reason, boolean judgeRan) {
    }

    private static final String BASE = System.getenv().getOrDefault("EVAL_LLM_BASE_URL", "https://wb2api.yingyue.cloud/v1");
    private static final String MODEL = System.getenv().getOrDefault("EVAL_LLM_MODEL", "cn:deepseek-v4.1-flash");
    private static final String API_KEY = System.getenv("EVAL_LLM_API_KEY");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean available() {
        return API_KEY != null && !API_KEY.isBlank();
    }

    public Verdict judge(String question, String answer, List<String> steps) throws IOException, InterruptedException {
        if (!available()) {
            return new Verdict(0, "裁判未配置（EVAL_LLM_API_KEY 缺失）", false);
        }
        StringBuilder stepText = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            stepText.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        String system = """
                你是严格的电商智能客服评估裁判。依据评分步骤逐条核查回答，输出 JSON：
                {"score": 0.0~1.0, "reason": "30 字内的判词，指出扣分点"}
                评分步骤：
                """ + stepText;
        String user = "用户输入：" + question + "\n\n客服回答：" + answer;
        String body = objectMapper.writeValueAsString(Map.of(
                "model", MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0.1,
                "max_tokens", 300,
                "reasoning_effort", "none"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(stripSlash(BASE) + "/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return new Verdict(0, "裁判调用失败 HTTP " + resp.statusCode(), false);
        }
        JsonNode node = objectMapper.readTree(resp.body());
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        Matcher m = Pattern.compile("\\{[\\s\\S]*\\}").matcher(content);
        if (!m.find()) {
            return new Verdict(0, "裁判输出无法解析: " + content, true);
        }
        JsonNode verdict = objectMapper.readTree(m.group());
        double score = Math.max(0, Math.min(1, verdict.path("score").asDouble(0)));
        return new Verdict(score, verdict.path("reason").asText(""), true);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
