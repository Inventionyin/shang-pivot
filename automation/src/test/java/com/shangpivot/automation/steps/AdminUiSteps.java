package com.shangpivot.automation.steps;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.ui.BrowserSession;
import com.shangpivot.automation.ui.pages.AdminDashboardPage;
import com.shangpivot.automation.ui.pages.AdminLoginPage;
import com.shangpivot.automation.ui.pages.AdminProductsPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

public class AdminUiSteps {

    private final ScenarioContext ctx;

    public AdminUiSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private BrowserSession session() {
        BrowserSession session = BrowserSession.current();
        Assertions.assertThat(session).as("UI 场景未启动浏览器").isNotNull();
        return session;
    }

    @When("我通过界面登录管理端")
    public void loginViaUi() {
        AdminLoginPage login = new AdminLoginPage(session());
        login.login(TestConfig.ADMIN_USERNAME, TestConfig.ADMIN_PASSWORD);
        login.expectDashboard();
        new AdminDashboardPage(session()).expectLoaded();
    }

    @Then("管理端看板应显示工作台和数字指标")
    public void assertDashboard() {
        new AdminDashboardPage(session()).expectStatValuesNumeric();
    }

    @Given("我已通过接口登录管理端并注入登录态")
    public void injectAdminState() {
        ApiResult login = ctx.auth().loginAdmin(TestConfig.ADMIN_USERNAME, TestConfig.ADMIN_PASSWORD)
                .assertSuccess();
        String token = login.data().path("accessToken").asText();
        ctx.adminToken(token);
        session().injectLocalStorage("shang_admin_token", token);
        String refresh = login.data().path("refreshToken").asText();
        if (!refresh.isBlank()) {
            session().injectLocalStorage("shang_admin_refresh", refresh);
        }
        String user = login.data().path("user").toString();
        if (!user.isBlank()) {
            session().injectLocalStorage("shang_admin_user", user);
        }
    }

    @When("我打开后台商品列表并搜索 {string}")
    public void searchProducts(String keyword) {
        AdminProductsPage products = new AdminProductsPage(session());
        products.openProducts();
        products.search(keyword);
    }

    @Then("后台商品列表每一行都应包含 {string}")
    public void assertRows(String keyword) {
        new AdminProductsPage(session()).expectAllRowsContain(keyword);
    }
}
