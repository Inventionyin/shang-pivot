package com.shangpivot.ui.support;

import com.shangpivot.ui.config.Env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 留痕：每轮一个目录，按用途分子目录。
 *
 * <pre>
 * artifacts/ui/&lt;runId&gt;/
 *   screenshots/&lt;场景&gt;.png     ← 每个场景一张（成功也留），同时挂进 Cucumber 报告
 *   failures/&lt;场景&gt;.png|.html|.zip ← 仅失败：整页截图 + 页面 DOM + Playwright trace
 *   run.json                      ← 本轮摘要
 * </pre>
 */
public final class Artifacts {

    /** 每场景一张的执行截图（成功/失败都留）。 */
    public static final String SCREENSHOTS = "screenshots";
    /** 失败取证：整页截图 + DOM + trace。 */
    public static final String FAILURES = "failures";

    private static final String RUN_ID = System.getenv("RUN_ID") != null && !System.getenv("RUN_ID").isBlank()
            ? System.getenv("RUN_ID")
            : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    private Artifacts() {
    }

    public static String runId() {
        return RUN_ID;
    }

    public static Path runDir() {
        return dir("");
    }

    /** 取（并确保存在）本轮留证下的子目录；传空串即本轮根目录。 */
    public static Path dir(String sub) {
        Path path = sub == null || sub.isBlank() ? Env.ARTIFACTS.resolve(RUN_ID) : Env.ARTIFACTS.resolve(RUN_ID).resolve(sub);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建留证目录 " + path, e);
        }
        return path;
    }

    public static Path file(String sub, String scenarioSlug, String suffix) {
        return dir(sub).resolve(safe(scenarioSlug) + suffix);
    }

    /** 用例名含中文与空格，作为文件名要做最小化处理。 */
    private static String safe(String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
