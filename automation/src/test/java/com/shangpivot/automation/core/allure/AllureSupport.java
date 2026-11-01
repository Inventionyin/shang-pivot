package com.shangpivot.automation.core.allure;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.config.TestConfig;
import io.qameta.allure.Allure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Allure 附件与环境信息。所有请求/响应在进入报告前统一脱敏。
 */
public final class AllureSupport {

    private static final Pattern SECRET_JSON = Pattern.compile(
            "(\"(?:accessToken|refreshToken|token|password|secret)\"\\s*:\\s*\")[^\"]+(\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]+");

    private AllureSupport() {
    }

    public static String mask(String content) {
        if (content == null) {
            return "";
        }
        String masked = SECRET_JSON.matcher(content).replaceAll("$1***$2");
        return BEARER.matcher(masked).replaceAll("$1***");
    }

    public static void attach(String name, String type, String content) {
        Allure.addAttachment(name, type, mask(content), ".txt");
    }

    public static void attachJson(String name, String json) {
        Allure.addAttachment(name, "application/json", mask(json), ".json");
    }

    public static void attachJson(String name, JsonNode node) {
        attachJson(name, node == null ? "{}" : node.toPrettyString());
    }

    public static void attachBytes(String name, String type, byte[] bytes, String extension) {
        Allure.addAttachment(name, type, new java.io.ByteArrayInputStream(bytes), extension);
    }

    public static void attachExchange(String method, String uri, String requestBody,
                                      int status, String responseBody, long durationMs) {
        String payload = """
                %s %s
                --- request ---
                %s

                --- response ---
                HTTP %d
                %s
                --- duration ---
                %d ms
                """.formatted(method, uri,
                requestBody == null || requestBody.isBlank() ? "(empty)" : requestBody,
                status,
                responseBody == null ? "(empty)" : responseBody,
                durationMs);
        Allure.addAttachment(method + " " + uri, "text/plain", mask(payload), ".txt");
    }

    /** 报告环境页：明确本次运行的地址、浏览器与数据环境。 */
    public static void writeEnvironment() {
        try {
            Path dir = TestConfig.allureResultsDir();
            Files.createDirectories(dir);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("run.id", TestConfig.runId());
            env.put("api.baseUrl", TestConfig.API_BASE_URL);
            env.put("mall.baseUrl", TestConfig.MALL_BASE_URL);
            env.put("admin.baseUrl", TestConfig.ADMIN_BASE_URL);
            env.put("ui.headless", String.valueOf(TestConfig.UI_HEADLESS));
            env.put("ui.viewport", TestConfig.UI_VIEWPORT_WIDTH + "x" + TestConfig.UI_VIEWPORT_HEIGHT);
            env.put("java.version", System.getProperty("java.version"));
            env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            env.put("ci", String.valueOf(TestConfig.isCi()));

            StringBuilder properties = new StringBuilder();
            env.forEach((k, v) -> properties.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve("environment.properties"), properties.toString(), StandardCharsets.UTF_8);

            Files.writeString(dir.resolve("categories.json"), """
                    [
                      {"name":"产品缺陷","matchedStatuses":["failed"],"messageRegex":".*AssertionError.*"},
                      {"name":"测试脚本缺陷","matchedStatuses":["broken"],"messageRegex":".*"},
                      {"name":"环境异常","matchedStatuses":["broken"],"messageRegex":".*(timeout|Connection|refused|不可用).*"}
                    ]
                    """, StandardCharsets.UTF_8);

            Files.writeString(dir.resolve("executor.json"), """
                    {
                      "name": "shang-pivot-automation",
                      "type": "maven",
                      "buildName": "run-%s",
                      "buildOrder": %d,
                      "reportName": "尚品汇 E2E 自动化报告"
                    }
                    """.formatted(TestConfig.runId(), System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 Allure 环境信息失败", e);
        }
    }
}
