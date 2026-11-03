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

    static {
        // 隧道直连（EVAL_LLM_INSECURE）时关闭 HttpClient 的强制主机名校验
        if ("true".equalsIgnoreCase(System.getenv("EVAL_LLM_INSECURE"))) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
    }

    public record Verdict(double score, String reason, boolean judgeRan) {
    }

    private static final String BASE = System.getenv().getOrDefault("EVAL_LLM_BASE_URL", "https://your-openai-compatible-gateway.example/v1");
    private static final String MODEL = System.getenv().getOrDefault("EVAL_LLM_MODEL", "cn:deepseek-v4.1-flash");
    private static final String API_KEY = System.getenv("EVAL_LLM_API_KEY");
    /** 经 SSH 隧道直连网关时（127.0.0.1 无公网证书），设置 EVAL_LLM_INSECURE=true 跳过证书校验（仅测试用）。 */
    private static final boolean INSECURE = "true".equalsIgnoreCase(System.getenv("EVAL_LLM_INSECURE"));

    private final HttpClient http = buildClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (INSECURE) {
            try {
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{new X509TrustAll()}, new java.security.SecureRandom());
                b.sslContext(ctx);
                javax.net.ssl.SSLParameters params = new javax.net.ssl.SSLParameters();
                params.setEndpointIdentificationAlgorithm(null);
                b.sslParameters(params);
            } catch (Exception ignored) {
                // 退化：按默认 TLS 处理
            }
        }
        return b.build();
    }

    private static final class X509TrustAll implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

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
                """ + stepText + """
                约束：
                1) 只依据评分步骤与回答本身判断；回答中出现步骤未提及的额外规格/价格，
                   只要不与步骤中的事实冲突，不得判为错误；
                2) 不要用你自己的商品知识臆断价格或库存；无法从回答确认的事实不要当作错误。
                """;
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
        // 裁判基础设施故障（DNS/超时/断连）不得中断评估：重试 2 次后降级为「裁判跳过」
        HttpResponse<String> resp = null;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    Thread.sleep(3_000L * attempt);
                }
            }
        }
        if (resp == null) {
            return new Verdict(0, "裁判网络不可用: " + (last == null ? "unknown" : last.getClass().getSimpleName()), false);
        }
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
