package com.shangpivot.ui.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 运行期配置：全部来自环境变量，默认值只指向公开的演示地址与演示账号。
 * 凭据一律不写进文件（与 api_testcases 同一约定）。
 */
public final class Env {

    /** 商城 C 端：锁 1280×720 走 Web 形态（<768px 会切到 H5，整套 DOM 不同）。 */
    public static final String MALL_URL = stripTrailingSlash(
            get("SHANG_MALL_URL", get("MALL_BASE_URL", "http://111.170.33.2:60084")));
    public static final String ADMIN_URL = stripTrailingSlash(
            get("SHANG_ADMIN_URL", get("ADMIN_BASE_URL", "http://111.170.33.2:60085")));

    public static final String APP_ACCOUNT = get("APP_TEST_ACCOUNT", "13800000001");
    public static final String APP_SMS_CODE = get("APP_TEST_SMS_CODE", "123456");
    public static final String ADMIN_ACCOUNT = get("ADMIN_TEST_ACCOUNT", "admin");
    public static final String ADMIN_PASSWORD = get("ADMIN_TEST_PASSWORD", "Admin@123");

    public static final boolean HEADLESS = Boolean.parseBoolean(get("UI_HEADLESS", "true"));
    public static final int SLOW_MO_MS = Integer.parseInt(get("UI_SLOWMO_MS", "0"));
    public static final int VIEWPORT_WIDTH = Integer.parseInt(get("UI_VIEWPORT_WIDTH", "1280"));
    public static final int VIEWPORT_HEIGHT = Integer.parseInt(get("UI_VIEWPORT_HEIGHT", "720"));
    public static final Duration TIMEOUT = Duration.ofMillis(Long.parseLong(get("UI_TIMEOUT_MS", "15000")));

    public static final Path ARTIFACTS = Path.of(get("UI_ARTIFACTS_DIR", "artifacts/ui"));
    /** 与后端约定一致：非 GET 请求也会带 Origin，此处仅供 API 预取使用。 */
    public static final String HTTP_ORIGIN = get("UI_HTTP_ORIGIN", MALL_URL);

    private Env() {
    }

    private static String get(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 启动前自检：目标不可达时明确失败，绝不静默跳过（否则"站点挂了"会显示成全绿）。 */
    public static void preflight() {
        requireReachable(MALL_URL, "/");
        requireReachable(ADMIN_URL, "/");
    }

    private static void requireReachable(String base, String path) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(base + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            int status = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 400) {
                throw new IllegalStateException("目标返回 " + status);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "环境自检失败：" + base + " 不可达（" + e.getMessage() + "）。"
                            + "UI 自动化不做静默跳过——请确认地址或设 SHANG_MALL_URL / SHANG_ADMIN_URL。", e);
        }
    }
}
