package com.shangpivot.automation.ui.pages;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 商城登录页 `/login`。 */
public final class MallLoginPage extends BasePage {

    public MallLoginPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openLogin() {
        open("/login");
        assertThat(page().getByPlaceholder("请输入 11 位手机号")).isVisible();
    }

    public void fillPhone(String phone) {
        page().getByPlaceholder("请输入 11 位手机号").fill(phone);
    }

    public void sendSmsCode() {
        page().locator("button.code-btn").click();
        expectToastContains("验证码已发送");
    }

    public void fillCode(String code) {
        page().getByPlaceholder("请输入 6 位验证码").fill(code);
    }

    public void submit() {
        page().locator("button.submit").click();
    }

    public void expectLoggedIn() {
        expectPath("/");
        waitVisible(page().locator(".web-utility").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText("Hi，")).first(), 15_000);
    }

    public void expectInlineError(String text) {
        assertThat(page().locator(".error-text").first()).containsText(text);
    }

    public void login(String phone, String code) {
        openLogin();
        fillPhone(phone);
        sendSmsCode();
        fillCode(code);
        submit();
    }
}
