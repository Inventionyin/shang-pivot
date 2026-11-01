package com.shangpivot.automation.core.env;

import com.shangpivot.automation.config.TestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行前预检：服务不可达时直接失败，而不是让用例大面积超时或被 skip（假绿）。
 */
public final class EnvironmentProbe {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentProbe.class);
    private static volatile boolean checked;

    private EnvironmentProbe() {
    }

    public static synchronized void verifyOnce() {
        if (checked) {
            return;
        }
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("API", TestConfig.API_BASE_URL + "/api/home");
        targets.put("商城", TestConfig.MALL_BASE_URL + "/");
        targets.put("管理端", TestConfig.ADMIN_BASE_URL + "/");

        // Vite dev server 对明文 HTTP/2 升级处理不友好，固定 HTTP/1.1，避免预检误报超时
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        Map<String, String> failures = new LinkedHashMap<>();
        targets.forEach((name, url) -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                boolean ok = response.statusCode() >= 200 && response.statusCode() < 400;
                log.info("预检 {} {} -> HTTP {}", name, url, response.statusCode());
                if (!ok) {
                    failures.put(name, url + " -> HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                failures.put(name, url + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        if (!failures.isEmpty()) {
            throw new IllegalStateException("""
                    本地被测环境不可用，已停止执行（不跳过、不假绿）。
                    失败项：%s
                    请先启动：bash automation/scripts/start-local.sh
                    也可以手动启动：
                      cd backend && docker compose -p shang-pivot-auto up -d postgres redis
                      cd backend && java -jar shang-server/target/shang-server-0.0.1-SNAPSHOT.jar
                      cd shang-admin-web && npm run dev -- --host 127.0.0.1 --port 5173
                      cd shang-mall && npm run dev -- --host 127.0.0.1 --port 5174
                    """.formatted(failures));
        }
        checked = true;
        log.info("环境预检通过：{}", TestConfig.describe());
    }
}
