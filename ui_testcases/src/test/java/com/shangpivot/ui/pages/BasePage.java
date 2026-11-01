package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.shangpivot.ui.support.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 页面对象基类：只管导航、等待与通用弹层，不含业务断言。 */
public abstract class BasePage {

    protected final BrowserSession session;

    protected BasePage(BrowserSession session) {
        this.session = session;
    }

    protected Page page() {
        return session.page();
    }

    protected abstract String baseUrl();

    public void open(String path) {
        page().navigate(baseUrl() + path, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }

    /** 注入 token 以跳过 UI 登录（仅当场景本身不测登录时使用）。 */
    public void injectToken(String storageKey, String token) {
        // Playwright Java 的 addInitScript 只有 (String)/(Path) 两种重载，没有带参版本，
        // 因此把值按 JSON 转义后拼进脚本（token 含引号也不会破）。
        try {
            String script = "window.localStorage.setItem("
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(storageKey) + ", "
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(token) + ")";
            page().addInitScript(script);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("token 序列化失败", e);
        }
    }

    /** 项目自定义弹层：`.pop-panel`，内容以标题区分。 */
    protected Locator popup(String title) {
        return page().locator(".pop-panel").filter(new Locator.FilterOptions().setHasText(title)).last();
    }

    protected void expectToastContains(String text) {
        // Vant toast 约 2s 自动消失，必须紧跟动作断言
        assertThat(page().locator(".van-toast")).containsText(text);
    }

    protected Locator button(String text) {
        return page().getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(text));
    }

    public void expectPath(String regex) {
        assertThat(page()).hasURL(java.util.regex.Pattern.compile(".*" + regex + ".*"));
    }
}
