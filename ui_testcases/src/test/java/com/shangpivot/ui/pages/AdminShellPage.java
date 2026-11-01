package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 管理端登录页 `/login` 与登录后的框架（侧栏 / 工作台）。 */
public class AdminShellPage extends BasePage {

    public AdminShellPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.ADMIN_URL;
    }

    // ---------------- 登录 ----------------

    public Locator adminUsernameInput() {
        return page().getByPlaceholder("请输入用户名");
    }

    public void loginAsAdmin() {
        open("/login");
        assertThat(adminUsernameInput()).isVisible();
        adminUsernameInput().fill(Env.ADMIN_ACCOUNT);
        page().getByPlaceholder("请输入密码").fill(Env.ADMIN_PASSWORD);
        // 按钮文案是「登 录」（中间有空格），用 class 选择器更稳
        page().locator("button.auth-btn").click();
        expectDashboard();
    }

    public void expectDashboard() {
        expectPath("/dashboard");
        assertThat(page().locator(".crumbs")).containsText("工作台");
        assertThat(page().locator(".brand-name")).hasText("Shang 后台");
    }

    // ---------------- 框架 ----------------

    /** 侧栏菜单项（后端菜单驱动；admin 账号拥有全部权限）。 */
    public Locator navItem(String name) {
        return page().locator(".nav-item").filter(new Locator.FilterOptions().setHasText(name)).first();
    }

    public void expectNavGroup(String groupTitle) {
        assertThat(page().locator(".nav-group-title").filter(
                new Locator.FilterOptions().setHasText(groupTitle)).first()).isVisible();
    }

    /** 工作台统计卡：`.stat-value` 至少有值，且不出现全 0 的异常。 */
    public void expectDashboardStats() {
        assertThat(page().locator(".stat-value").first()).isVisible();
    }

    public void clickNav(String name) {
        navItem(name).click();
    }

    /**
     * 点侧栏并等 URL 真的变过去。跳转失败时把「当前 URL + 页面提示」带进失败信息，
     * 否则只会看到一条"元素没找到"，无法定位是权限拦截还是渲染失败。
     */
    public void clickNavAndWait(String name, String expectedPath) {
        navItem(name).click();
        try {
            page().waitForURL(java.util.regex.Pattern.compile(".*" + expectedPath + ".*"),
                    new com.microsoft.playwright.Page.WaitForURLOptions().setTimeout(8000));
        } catch (Exception e) {
            java.util.List<String> prompts = page().locator(".el-message, .van-toast").allInnerTexts();
            throw new AssertionError("点击侧栏「" + name + "」后未跳转到 " + expectedPath
                    + "；当前 URL=" + page().url() + "；页面提示=" + prompts, e);
        }
    }
}
