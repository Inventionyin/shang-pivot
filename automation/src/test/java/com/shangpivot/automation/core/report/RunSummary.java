package com.shangpivot.automation.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shangpivot.automation.config.TestConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行摘要：机器可读的 run-summary.json，便于 CI 聚合与趋势分析。
 */
public final class RunSummary {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, ScenarioResult> RESULTS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    private static final Instant STARTED = Instant.now();

    private RunSummary() {
    }

    public static void record(String name, Iterable<String> tags, boolean failed, long durationMs, String error) {
        // Scenario Outline 的多个 Examples 行名称相同，必须用递增序号区分，否则会互相覆盖
        RESULTS.put(name + "#" + SEQ.incrementAndGet(),
                new ScenarioResult(name, String.join(",", tags), failed, durationMs, error));
    }

    public static synchronized void write() {
        try {
            Path dir = TestConfig.runDir();
            ObjectNode root = MAPPER.createObjectNode();
            root.put("runId", TestConfig.runId());
            root.put("startedAt", STARTED.toString());
            root.put("finishedAt", Instant.now().toString());
            root.put("apiBaseUrl", TestConfig.API_BASE_URL);
            root.put("mallBaseUrl", TestConfig.MALL_BASE_URL);
            root.put("adminBaseUrl", TestConfig.ADMIN_BASE_URL);

            long passed = RESULTS.values().stream().filter(r -> !r.failed).count();
            long failed = RESULTS.values().stream().filter(r -> r.failed).count();
            ObjectNode summary = root.putObject("summary");
            summary.put("total", RESULTS.size());
            summary.put("passed", passed);
            summary.put("failed", failed);
            summary.put("passRate", RESULTS.isEmpty() ? 0 : Math.round(passed * 1000.0 / RESULTS.size()) / 10.0);

            ArrayNode scenarios = root.putArray("scenarios");
            RESULTS.values().stream()
                    .sorted((a, b) -> a.name.compareTo(b.name))
                    .forEach(r -> {
                        ObjectNode node = scenarios.addObject();
                        node.put("name", r.name);
                        node.put("tags", r.tags);
                        node.put("status", r.failed ? "failed" : "passed");
                        node.put("durationMs", r.durationMs);
                        if (r.error != null) {
                            node.put("error", r.error);
                        }
                    });

            Files.createDirectories(dir);
            Path file = dir.resolve("run-summary.json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (Exception e) {
            System.err.println("[run-summary] 写入失败: " + e.getMessage());
        }
    }

    private record ScenarioResult(String name, String tags, boolean failed, long durationMs, String error) {
    }
}
