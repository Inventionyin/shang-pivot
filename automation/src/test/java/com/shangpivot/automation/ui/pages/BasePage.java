package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.automation.core.ui.BrowserSession;

import java.util.regex.Pattern;

import com.microsoft.playwright.assertions.PageAssertions;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 页面对象基类：导航、等待、通用弹层与 Toast；不做业务断言编排。 */
public abstract class BasePage {

    protected final BrowserSession session;
    protected final String baseUrl;

    protected BasePage(BrowserSession session, String baseUrl) {
        this.session = session;
        this.baseUrl = baseUrl;
    }

    protected Page page() {
        return session.page();
    }

    public void open(String path) {
        session.gotoUrl(baseUrl + path);
        session.waitForDom();
    }

    public void expectPath(String fragment) {
        // Playwright 把 Pattern 序列化成 JS 正则，JS 不支持 \Q...\E，因此这里只做普通匹配。
        // 传入的都是路径片段（如 /product/、/dashboard），不含正则元字符。
        // CI 冷启动下 SPA 首屏/结算页导航可能超过 15s（Vite dev 首次编译），统一放宽到 30s
        assertThat(page()).hasURL(Pattern.compile(".*" + fragment + ".*"),
                new PageAssertions.HasURLOptions().setTimeout(30_000));
    }

    /** 非断言式等待 URL 片段（用于需要容错重试的场景，如 Vite 依赖预构建触发整页刷新）。 */
    public boolean waitForPath(String fragment, double timeoutMs) {
        try {
            page().waitForURL(Pattern.compile(".*" + fragment + ".*"),
                    new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(timeoutMs));
            return true;
        } catch (com.microsoft.playwright.TimeoutError e) {
            return false;
        }
    }

    protected Locator popup(String title) {
        return page().locator(".pop-panel")
                .filter(new Locator.FilterOptions().setHasText(title)).last();
    }

    protected Locator button(String name) {
        return page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name));
    }

    /** Vant Toast 约 2 秒消失，必须在触发动作后立即断言。 */
    protected void expectToastContains(String text) {
        assertThat(page().locator(".van-toast")).containsText(text);
    }

    protected void waitVisible(Locator locator, int timeoutMs) {
        locator.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMs));
        assertThat(locator).isVisible();
    }

    public void waitTextVisible(String text) {
        waitVisible(page().getByText(text).first(), 15_000);
    }

    public void expectTextVisible(String text) {
        assertThat(page().getByText(text).first()).isVisible();
    }
}
