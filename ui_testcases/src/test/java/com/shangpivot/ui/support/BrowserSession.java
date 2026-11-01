package com.shangpivot.ui.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.shangpivot.ui.config.Env;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 每个 scenario 一个独立 BrowserContext（互不串味），但共用同一个 Playwright/Browser 进程。
 * <p>
 * 截图策略：**每个场景都截一张**（成功/失败都留，挂进报告）；
 * 失败时额外留整页截图 + 页面 DOM + trace。成功时丢弃 trace（不堆垃圾）。
 */
public final class BrowserSession implements AutoCloseable {

    private static final Object LOCK = new Object();
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;
    private Path tracePath;

    private static Playwright playwright() {
        synchronized (LOCK) {
            if (playwright == null) {
                playwright = Playwright.create();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    synchronized (LOCK) {
                        if (browser != null) {
                            browser.close();
                        }
                        playwright.close();
                    }
                }));
            }
            return playwright;
        }
    }

    private static Browser browser() {
        synchronized (LOCK) {
            if (browser == null || !browser.isConnected()) {
                browser = playwright().chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(Env.HEADLESS)
                        .setSlowMo(Env.SLOW_MO_MS));
            }
            return browser;
        }
    }

    public Page page() {
        if (page == null) {
            throw new IllegalStateException("会话尚未启动，请先调用 start()");
        }
        return page;
    }

    public BrowserContext context() {
        return context;
    }

    public void start(String scenarioSlug) {
        // Playwright 断言默认只等 5s，远程 SPA 的懒加载路由 chunk 偶发更慢（chunk 未到时根节点是空的），
        // 统一提到配置的等待上限，避免把"加载慢"误报成"功能坏"。
        com.microsoft.playwright.assertions.PlaywrightAssertions
                .setDefaultAssertionTimeout(Env.TIMEOUT.toMillis());
        context = browser().newContext(new Browser.NewContextOptions()
                // 锁定视口：商城 <768px 会切到 H5 形态，DOM 完全不同
                .setViewportSize(Env.VIEWPORT_WIDTH, Env.VIEWPORT_HEIGHT)
                .setLocale("zh-CN")
                .setIgnoreHTTPSErrors(true));
        context.setDefaultTimeout(Env.TIMEOUT.toMillis());
        // 导航超时不跟着元素等待时间走：本地负载高时远程 SPA 的首次导航会明显变慢。
        // Playwright 框架默认就是 30s，这里不低于该值，避免把"机器忙"误报成"页面坏"。
        context.setDefaultNavigationTimeout(Math.max(30_000, Env.TIMEOUT.toMillis() + 10_000));
        tracePath = Artifacts.file(Artifacts.FAILURES, scenarioSlug, ".zip");
        // trace 只留截图，不留 DOM 快照：快照会在每个动作后序列化整个页面，本地负载高时把导航拖到超时。
        // 失败时的 DOM 由 Artifacts.FAILURES 里的整页截图 + 页面 HTML 兜底，取证不缺料。
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true).setSnapshots(false).setSources(false));
        page = context.newPage();
    }

    /**
     * 收尾：无论成败都截一张执行截图并写盘；失败再加整页截图 + DOM + trace。
     *
     * @return 执行截图的字节（供 Cucumber 报告内嵌）；会话未启动时返回 null
     */
    public byte[] finish(String scenarioSlug, boolean failed) {
        if (context == null) {
            return null;
        }
        byte[] screenshot = null;
        try {
            // 视口截图：报告里看着舒服、体积可控
            screenshot = page.screenshot();
            Files.write(Artifacts.file(Artifacts.SCREENSHOTS, scenarioSlug, ".png"), screenshot);
            if (failed) {
                // 失败取证用整页截图 + DOM + trace，便于定位"当初页面到底长什么样"
                Files.write(Artifacts.file(Artifacts.FAILURES, scenarioSlug, ".png"),
                        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)));
                Files.writeString(Artifacts.file(Artifacts.FAILURES, scenarioSlug, ".html"), page.content());
                context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
            } else {
                context.tracing().stop();
                Files.deleteIfExists(tracePath);
            }
        } catch (Exception e) {
            System.err.println("[ui-artifacts] 留证失败：" + e.getMessage());
        } finally {
            context.close();
            context = null;
            page = null;
        }
        return screenshot;
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
