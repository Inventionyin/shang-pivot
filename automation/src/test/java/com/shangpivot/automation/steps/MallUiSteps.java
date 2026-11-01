package com.shangpivot.automation.steps;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.data.TestData;
import com.shangpivot.automation.core.ui.BrowserSession;
import com.shangpivot.automation.ui.pages.MallHomePage;
import com.shangpivot.automation.ui.pages.MallLoginPage;
import com.shangpivot.automation.ui.pages.MallProductPage;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

public class MallUiSteps {

    private final ScenarioContext ctx;

    public MallUiSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private BrowserSession session() {
        BrowserSession session = BrowserSession.current();
        Assertions.assertThat(session).as("UI 场景未启动浏览器，请检查 @ui 标签").isNotNull();
        return session;
    }

    @When("我打开商城首页")
    public void openHome() {
        new MallHomePage(session()).openHome();
    }

    @Then("首页应展示品牌名和商品卡片")
    public void assertHome() {
        MallHomePage home = new MallHomePage(session());
        home.expectBrandRendered();
    }

    @When("我打开第一个商品详情")
    public void openFirstProduct() {
        ctx.productTitle(new MallHomePage(session()).openFirstProduct());
    }

    @Then("商品详情应显示标题")
    public void assertProductDetail() {
        MallProductPage detail = new MallProductPage(session());
        Assertions.assertThat(detail.title()).isNotBlank();
        Assertions.assertThat(detail.title()).isEqualTo(ctx.productTitle());
    }

    @When("我用新手机号和演示验证码通过界面登录商城")
    public void loginNewCustomer() {
        MallLoginPage login = new MallLoginPage(session());
        login.login(ctx.phone(), TestConfig.DEMO_SMS_CODE);
    }

    @Then("商城应显示已登录状态")
    public void assertLoggedIn() {
        new MallLoginPage(session()).expectLoggedIn();
    }

    @When("我用演示会员手机号和错误验证码通过界面登录商城")
    public void loginWithWrongCode() {
        MallLoginPage login = new MallLoginPage(session());
        login.openLogin();
        // 用唯一手机号，避免演示会员的 60 秒短信冷却导致场景互相干扰
        login.fillPhone(ctx.phone());
        login.sendSmsCode();
        login.fillCode("000000");
        login.submit();
    }

    @Then("登录页应提示 {string}")
    public void assertInlineError(String text) {
        new MallLoginPage(session()).expectInlineError(text);
    }

    @When("我打开搜索页搜索不存在关键词")
    public void searchMissingKeyword() {
        MallHomePage home = new MallHomePage(session());
        home.open("/search?q=" + java.net.URLEncoder.encode(TestData.randomKeywordMiss(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Then("搜索页应展示空态和推荐")
    public void assertSearchEmpty() {
        MallHomePage home = new MallHomePage(session());
        home.waitTextVisible("没有找到相关商品");
        home.expectTextVisible("为你推荐");
    }
}
