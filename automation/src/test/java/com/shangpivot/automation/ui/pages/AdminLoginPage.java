package com.shangpivot.automation.ui.pages;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 管理端登录 `/login`（注意按钮文案是「登 录」，中间有空格）。 */
public final class AdminLoginPage extends BasePage {

    public AdminLoginPage(BrowserSession session) {
        super(session, TestConfig.ADMIN_BASE_URL);
    }

    public void openLogin() {
        open("/login");
        assertThat(page().locator("input[name=username]")).isVisible();
    }

    public void login(String username, String password) {
        openLogin();
        page().locator("input[name=username]").fill(username);
        page().locator("input[name=password]").fill(password);
        page().locator("button.auth-btn").click();
    }

    public void expectDashboard() {
        expectPath("/dashboard");
    }
}
