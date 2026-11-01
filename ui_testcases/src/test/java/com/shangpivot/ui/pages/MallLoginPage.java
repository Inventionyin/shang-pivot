package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 商城登录页 `/login`（原生 input，非 van-field）。 */
public class MallLoginPage extends BasePage {

    public static final String ACCESS_KEY = "shang_mall_access";

    public MallLoginPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.MALL_URL;
    }

    public void openLogin() {
        open("/login");
        assertThat(phoneInput()).isVisible();
    }

    public Locator phoneInput() {
        return page().getByPlaceholder("请输入 11 位手机号");
    }

    public Locator codeInput() {
        return page().getByPlaceholder("请输入 6 位验证码");
    }

    public void fillPhone(String phone) {
        phoneInput().fill(phone);
    }

    public void fillCode(String code) {
        codeInput().fill(code);
    }

    public void clickSendCode() {
        page().locator("button.code-btn").click();
    }

    public void submit() {
        page().locator("button.submit").click();
    }

    /** 用演示账号走完整 UI 登录，并断言真的登录成功（顶栏出现 `Hi，`）。 */
    public void loginAsDemoMember() {
        openLogin();
        fillPhone(Env.APP_ACCOUNT);
        fillCode(Env.APP_SMS_CODE);
        submit();
        expectLoggedIn();
    }

    public void expectLoggedIn() {
        // 登录成功 → LoginView 会 replace 到 redirect 或 '/'。先等 URL 离开登录页，
        // 再断言顶栏登录态（顶栏在未登录时也存在，单看它无法区分"还没跳"和"跳了但没登录"）。
        page().waitForURL(java.util.regex.Pattern.compile(".*(?<![/]login)$"),
                new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(Env.TIMEOUT.toMillis()));
        assertThat(page().locator(".web-utility")).containsText("Hi，");
    }

    /** 行内校验提示（不是 toast）：`.error-text`。 */
    public Locator inlineError() {
        return page().locator(".error-text");
    }
}
