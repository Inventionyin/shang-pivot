package com.shangpivot.automation.core.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.LoadState;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.allure.AllureSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 浏览器生命周期：每个场景独立 context，失败保留全页截图、DOM、控制台与 trace。
 *
 * <p>Playwright 实例不是线程安全的，因此按线程持有 Playwright/Browser；场景 context 每次重建。</p>
 */
public final class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private static final ThreadLocal<Backend> BACKEND = new ThreadLocal<>();
    private static final ThreadLocal<BrowserSession> CURRENT = new ThreadLocal<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Backend backend = BACKEND.get();
            if (backend != null) {
                backend.close();
                BACKEND.remove();
            }
        }));
    }

    private BrowserContext context;
    private Page page;
    private String scenarioName;
    private Path scenarioDir;
    private final List<String> consoleLines = new CopyOnWriteArrayList<>();

    public static BrowserSession current() {
        return CURRENT.get();
    }

    public void start(String name) {
        finish(name, false, true);
        this.scenarioName = sanitize(name);
        this.consoleLines.clear();
        this.scenarioDir = TestConfig.runDir().resolve("screenshots");
        Backend backend = BACKEND.get();
        if (backend == null) {
            backend = new Backend();
            BACKEND.set(backend);
        }
        this.context = backend.browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(TestConfig.UI_VIEWPORT_WIDTH, TestConfig.UI_VIEWPORT_HEIGHT)
                .setLocale("zh-CN")
                .setIgnoreHTTPSErrors(true));
        this.context.setDefaultTimeout(TestConfig.UI_TIMEOUT_MS);
        this.context.setDefaultNavigationTimeout(Math.max(30_000, TestConfig.UI_TIMEOUT_MS + 10_000));
        this.context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        this.page = context.newPage();
        this.page.onConsoleMessage(msg -> consoleLines.add("[%s] %s".formatted(msg.type(), msg.text())));
        CURRENT.set(this);
        log.info("[ui] 场景浏览器已启动: {} ({}x{}, headless={})",
                name, TestConfig.UI_VIEWPORT_WIDTH, TestConfig.UI_VIEWPORT_HEIGHT, TestConfig.UI_HEADLESS);
    }

    public Page page() {
        if (page == null) {
            throw new IllegalStateException("浏览器尚未启动，请检查 Hooks 是否执行");
        }
        return page;
    }

    public void gotoUrl(String url) {
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(
                com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
    }

    /** 在页面脚本执行前写入 localStorage，用于 API 登录后直接进入已登录态。 */
    public void injectLocalStorage(String key, String value) {
        String script = "localStorage.setItem(%s, %s);".formatted(jsString(key), jsString(value));
        context.addInitScript(script);
    }

    public void waitForDom() {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    public void finish(String name, boolean failed) {
        finish(name, failed, false);
    }

    private void finish(String name, boolean failed, boolean silent) {
        if (context == null) {
            return;
        }
        String safeName = sanitize(name);
        try {
            if (!silent) {
                Path screenshot = scenarioDir.resolve(safeName + ".png");
                Files.createDirectories(scenarioDir);
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
                AllureSupport.attachBytes((failed ? "失败截图 · " : "执行截图 · ") + name,
                        "image/png", Files.readAllBytes(screenshot), ".png");

                if (failed) {
                    Path dir = TestConfig.runDir().resolve("failures");
                    Files.createDirectories(dir);
                    Path html = dir.resolve(safeName + ".html");
                    Files.writeString(html, page.content(), StandardCharsets.UTF_8);
                    AllureSupport.attach((failed ? "失败页面 DOM · " : "页面 DOM · ") + name,
                            "text/html", page.content());
                    Path consoleLog = dir.resolve(safeName + "-console.log");
                    Files.writeString(consoleLog,
                            consoleLines.isEmpty() ? "(no console output)" : String.join("\n", consoleLines),
                            StandardCharsets.UTF_8);
                    AllureSupport.attach("浏览器控制台 · " + name, "text/plain", String.join("\n", consoleLines));
                    Path trace = dir.resolve(safeName + ".zip");
                    context.tracing().stop(new Tracing.StopOptions().setPath(trace));
                    AllureSupport.attachBytes("Playwright trace · " + name,
                            "application/zip", Files.readAllBytes(trace), ".zip");
                } else {
                    context.tracing().stop();
                }
            }
        } catch (Exception e) {
            log.warn("[ui] 保存场景留证失败: {}", e.getMessage());
        } finally {
            try {
                context.close();
            } catch (Exception ignored) {
                // 浏览器可能已被关闭
            }
            context = null;
            page = null;
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
        }
    }

    @Override
    public void close() {
        finish(scenarioName == null ? "unknown" : scenarioName, false, true);
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class Backend {
        private final Playwright playwright;
        private final Browser browser;

        private Backend() {
            this.playwright = Playwright.create();
            this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(TestConfig.UI_HEADLESS));
            log.info("[ui] Chromium 已启动");
        }

        private void close() {
            try {
                browser.close();
            } catch (Exception ignored) {
            }
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
        }
    }
}
