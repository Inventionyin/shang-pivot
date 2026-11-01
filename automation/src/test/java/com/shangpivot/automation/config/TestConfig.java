package com.shangpivot.automation.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单一配置入口：所有环境差异都从环境变量读取，代码里只保留本地演示默认值。
 *
 * <p>支持 {@code SHANG_} 前缀（推荐）与无前缀两种写法，便于 CI 注入：</p>
 * <pre>
 * SHANG_API_BASE_URL     默认 http://127.0.0.1:8086
 * SHANG_MALL_BASE_URL    默认 http://127.0.0.1:5174
 * SHANG_ADMIN_BASE_URL   默认 http://127.0.0.1:5173
 * SHANG_UI_HEADLESS      默认 true
 * SHANG_UI_VIEWPORT      默认 1280x720（商城 <768 会切 H5，必须固定）
 * SHANG_UI_TIMEOUT_MS    默认 15000
 * SHANG_ARTIFACTS_DIR    默认 automation/artifacts
 * SHANG_RUN_ID           默认当前时间戳
 * SHANG_DEMO_SMS_CODE    默认 123456
 * SHANG_ADMIN_USERNAME   默认 admin
 * SHANG_ADMIN_PASSWORD   默认 Admin@123
 * </pre>
 */
public final class TestConfig {

    private static final String RUN_ID = read("RUN_ID",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

    public static final String API_BASE_URL = stripTrailingSlash(read("API_BASE_URL", "http://127.0.0.1:8086"));
    public static final String MALL_BASE_URL = stripTrailingSlash(read("MALL_BASE_URL", "http://127.0.0.1:5174"));
    public static final String ADMIN_BASE_URL = stripTrailingSlash(read("ADMIN_BASE_URL", "http://127.0.0.1:5173"));

    public static final boolean UI_HEADLESS = Boolean.parseBoolean(read("UI_HEADLESS", "true"));
    public static final int UI_TIMEOUT_MS = Integer.parseInt(read("UI_TIMEOUT_MS", "15000"));
    public static final int UI_VIEWPORT_WIDTH = Integer.parseInt(read("UI_VIEWPORT_WIDTH", "1280"));
    public static final int UI_VIEWPORT_HEIGHT = Integer.parseInt(read("UI_VIEWPORT_HEIGHT", "720"));

    public static final String DEMO_SMS_CODE = read("DEMO_SMS_CODE", "123456");
    public static final String ADMIN_USERNAME = read("ADMIN_USERNAME", "admin");
    public static final String ADMIN_PASSWORD = read("ADMIN_PASSWORD", "Admin@123");

    public static final Path ARTIFACT_ROOT = Path.of(read("ARTIFACTS_DIR", "artifacts")).toAbsolutePath();

    private TestConfig() {
    }

    public static String runId() {
        return RUN_ID;
    }

    public static Path runDir() {
        Path dir = ARTIFACT_ROOT.resolve("run-" + RUN_ID);
        try {
            Files.createDirectories(dir.resolve("screenshots"));
            Files.createDirectories(dir.resolve("failures"));
        } catch (Exception e) {
            throw new IllegalStateException("无法创建运行产物目录: " + dir, e);
        }
        return dir;
    }

    public static Path allureResultsDir() {
        return Path.of(System.getProperty("allure.results.directory", "target/allure-results")).toAbsolutePath();
    }

    /** CI 中通常设置为 true，用于关闭交互式行为。 */
    public static boolean isCi() {
        return "true".equalsIgnoreCase(read("CI", "false"));
    }

    public static String describe() {
        return "run=" + RUN_ID
                + ", api=" + API_BASE_URL
                + ", mall=" + MALL_BASE_URL
                + ", admin=" + ADMIN_BASE_URL
                + ", headless=" + UI_HEADLESS
                + ", viewport=" + UI_VIEWPORT_WIDTH + "x" + UI_VIEWPORT_HEIGHT;
    }

    private static String read(String key, String defaultValue) {
        String prefixed = System.getenv("SHANG_" + key);
        if (prefixed != null && !prefixed.isBlank()) {
            return prefixed.trim();
        }
        String plain = System.getenv(key);
        if (plain != null && !plain.isBlank()) {
            return plain.trim();
        }
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        return defaultValue;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
