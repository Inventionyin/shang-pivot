package com.shangpivot.ui.steps;

import com.microsoft.playwright.Page;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.pages.AdminProductsPage;
import com.shangpivot.ui.pages.AdminShellPage;
import com.shangpivot.ui.pages.MallCartPage;
import com.shangpivot.ui.pages.MallCatalogPage;
import com.shangpivot.ui.pages.MallLoginPage;
import com.shangpivot.ui.pages.MallOrdersPage;
import com.shangpivot.ui.support.ApiClient;
import com.shangpivot.ui.support.BrowserSession;
import com.shangpivot.ui.support.ScenarioContext;
import io.cucumber.java.en.When;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import com.fasterxml.jackson.databind.JsonNode;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 全部步骤定义（本套件规模下按"域"分四个内聚类）。 */
public class CommonSteps {

    private final BrowserSession session;
    private final ScenarioContext context;
    private final MallLoginPage loginPage;
    private final MallCatalogPage catalogPage;
    private final MallCartPage cartPage;
    private final MallOrdersPage ordersPage;
    private final AdminShellPage adminShell;
    private final AdminProductsPage adminProducts;

    public CommonSteps(BrowserSession session, ScenarioContext context) {
        this.session = session;
        this.context = context;
        this.loginPage = new MallLoginPage(session);
        this.catalogPage = new MallCatalogPage(session);
        this.cartPage = new MallCartPage(session);
        this.ordersPage = new MallOrdersPage(session);
        this.adminShell = new AdminShellPage(session);
        this.adminProducts = new AdminProductsPage(session);
    }

    private Page page() {
        return session.page();
    }

    // ---------------- 环境自检 ----------------

    @Given("商城站点可访问")
    public void mallReachable() {
        page().navigate(Env.MALL_URL, new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        assertThat(page()).hasTitle(java.util.regex.Pattern.compile("尚品汇"));
    }

    @And("管理端站点可访问")
    public void adminReachable() {
        page().navigate(Env.ADMIN_URL + "/login", new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        assertThat(adminShell.adminUsernameInput()).isVisible();
    }

    @Then("页面标题应包含商城名称")
    public void expectMallTitle() {
        assertThat(page()).hasTitle(java.util.regex.Pattern.compile("尚品汇"));
    }

    @Then("应看到管理端登录表单")
    public void expectAdminLoginForm() {
        assertThat(adminShell.adminUsernameInput()).isVisible();
        assertThat(page().locator("button.auth-btn")).isVisible();
    }

    // ---------------- 商城登录 ----------------

    @Given("会员打开登录页")
    public void openLogin() {
        loginPage.openLogin();
    }

    @Given("会员打开登录页并填入演示手机号")
    public void openLoginWithPhone() {
        loginPage.openLogin();
        loginPage.fillPhone(Env.APP_ACCOUNT);
    }

    @When("填入演示手机号")
    public void fillDemoPhone() {
        loginPage.fillPhone(Env.APP_ACCOUNT);
    }

    @When("用演示手机号与固定验证码登录")
    public void loginWithDemoAccount() {
        loginPage.fillPhone(Env.APP_ACCOUNT);
        loginPage.fillCode(Env.APP_SMS_CODE);
        loginPage.submit();
    }

    @When("填入固定验证码并提交")
    public void fillDemoCodeAndSubmit() {
        loginPage.fillCode(Env.APP_SMS_CODE);
        loginPage.submit();
    }

    @When("提交登录表单")
    public void submitLoginForm() {
        loginPage.submit();
    }

    @Then("应登录成功并进入商城")
    public void expectLoggedIn() {
        loginPage.expectLoggedIn();
    }

    @When("先点获取验证码")
    public void clickSendCode() {
        loginPage.clickSendCode();
    }

    @Then("应提示验证码已发送")
    public void expectCodeSent() {
        assertThat(page().locator(".van-toast")).containsText("验证码已发送");
    }

    @When("填入错误的验证码 {string} 并提交")
    public void loginWithWrongCode(String wrongCode) {
        loginPage.fillPhone(Env.APP_ACCOUNT);
        loginPage.fillCode(wrongCode);
        loginPage.submit();
    }

    /** 验证码错误是**行内错误**（LoginForm 的 catch 里写 codeError），不是 toast。 */
    @Then("应提示验证码错误")
    public void expectWrongCode() {
        assertThat(loginPage.inlineError().first()).containsText("验证码错误");
    }

    @When("填入不合法的手机号 {string}")
    public void fillInvalidPhone(String phone) {
        loginPage.fillPhone(phone);
        loginPage.fillCode(Env.APP_SMS_CODE);
    }

    @Then("应给出手机号格式的行内错误")
    public void expectInlinePhoneError() {
        assertThat(loginPage.inlineError().first()).containsText("请输入正确的手机号");
    }

    // ---------------- 商城浏览与加购 ----------------

    @Given("会员已通过界面登录")
    public void memberLoggedIn() {
        loginPage.loginAsDemoMember();
    }

    @When("进入首页并打开第一个商品的详情")
    public void openFirstProduct() {
        catalogPage.openHome();
        catalogPage.expectProductsRendered();
        context.productTitle(catalogPage.openFirstProductDetail());
    }

    @And("选择第一个有货规格并加入购物车")
    public void addToCart() {
        catalogPage.addCurrentProductToCart();
        // 记下商品名，便于购物车断言与收尾清理按标题定位
        if (context.productTitle() == null) {
            context.productTitle(catalogPage.currentTitle());
        }
    }

    @Then("购物车中应出现该商品")
    public void expectProductInCart() {
        cartPage.openCart();
        cartPage.expectProductInCart(context.productTitle());
    }

    @Given("购物车中已有一件商品")
    public void cartHasOneProduct() {
        catalogPage.openHome();
        catalogPage.expectProductsRendered();
        context.productTitle(catalogPage.openFirstProductDetail());
        catalogPage.addCurrentProductToCart();
    }

    @When("不勾选任何商品直接去结算")
    public void checkoutWithoutSelection() {
        context.prepareCartSelection(null);
        cartPage.openCart();
        if (cartPage.selectedCount() != 0) {
            throw new AssertionError("已把所有行置为未勾选，页脚却仍显示已选 " + cartPage.selectedCount() + " 件");
        }
        cartPage.clickCheckout();
    }

    @Then("应提示先勾选商品")
    public void expectSelectionToast() {
        cartPage.expectCheckoutBlockedBySelectionToast();
    }

    @When("勾选该商品并去结算")
    public void selectAndCheckout() {
        context.prepareCartSelection(context.productTitle());
        cartPage.openCart();
        if (cartPage.selectedCount() == 0) {
            throw new AssertionError("准备勾选后页脚仍显示已选 0 件");
        }
        cartPage.clickCheckout();
    }

    @Then("应进入确认订单页并展示应付金额")
    public void expectCheckoutPage() {
        cartPage.expectOnCheckout();
        cartPage.expectAddressSectionRendered();
        cartPage.expectAddressSelected();
        if (cartPage.goodsLineCount() != 1) {
            throw new AssertionError("确认订单页应只带勾选的那一件，实际商品行数=" + cartPage.goodsLineCount());
        }
        cartPage.expectPayableLooksLikeMoney();
        cartPage.clickSubmitOrder();
        cartPage.expectConfirmDialog();
    }

    // ---------------- 商城订单状态流转 ----------------

    @Given("通过接口造一笔待支付订单")
    public void createPendingOrder() {
        // 用接口造数（UI 不适合造前置数据），但状态流转本身全部走界面验证
        JsonNode items = ApiClient.get(Env.MALL_URL + "/api/cart", context.mallTokens().accessToken()).path("items");
        String spuId = null;
        String skuId = null;
        int quantity = 1;
        for (JsonNode item : items) {
            // 购物车行直接带 spoId/skuId，无需再查商品详情
            if (item.path("spuId").asText().isBlank() || item.path("skuId").asText().isBlank()) {
                continue;
            }
            spuId = item.path("spuId").asText();
            skuId = item.path("skuId").asText();
            quantity = Math.max(1, item.path("quantity").asInt(1));
            break;
        }
        if (skuId == null) {
            throw new IllegalStateException("购物车里没有可下单的行（前置场景应先加购一件商品）");
        }
        JsonNode addresses = ApiClient.get(Env.MALL_URL + "/api/addresses", context.mallTokens().accessToken());
        if (!addresses.isArray() || addresses.isEmpty()) {
            throw new IllegalStateException("账号 " + Env.APP_ACCOUNT + " 没有收货地址，无法下单");
        }
        JsonNode order = ApiClient.post(Env.MALL_URL + "/api/orders", java.util.Map.of(
                "addressId", addresses.get(0).path("id").asLong(),
                "spuId", Long.parseLong(spuId),
                "skuId", Long.parseLong(skuId),
                "quantity", quantity,
                "idempotentKey", "UITEST-" + System.currentTimeMillis()), context.mallTokens().accessToken());
        context.createdOrder(order.path("orderId").asText());
    }

    @When("在订单详情页取消该订单")
    public void cancelOrderInUi() {
        ordersPage.openOrderDetail(context.createdOrderId());
        ordersPage.expectStatus("PENDING_PAY");
        ordersPage.cancelOrderAndExpectClosed();
    }

    @Then("订单状态应变为已关闭")
    public void expectOrderClosed() {
        ordersPage.openOrderList();
        ordersPage.expectCardStatus(context.createdOrderId(), "已关闭");
    }

    // ---------------- 管理端 ----------------

    @Given("管理员打开管理端并登录")
    public void adminLogin() {
        adminShell.loginAsAdmin();
    }

    @Then("应进入工作台且在售指标有值")
    public void expectDashboard() {
        adminShell.expectDashboardStats();
        adminShell.expectNavGroup("商品中心");
    }

    @When("通过侧栏进入商品列表")
    public void gotoProducts() {
        adminShell.clickNavAndWait("商品列表", "/products");
        adminProducts.expectLoaded();
    }

    @Then("商品表格应有数据且分页可用")
    public void expectProductRows() {
        adminProducts.expectRowsRendered();
        adminProducts.expectPagerVisible();
    }

    @When("按商品名称关键字 {string} 查询")
    public void searchProducts(String keyword) {
        adminProducts.search(keyword);
    }

    @Then("查询结果应包含关键字 {string}")
    public void expectSearchResult(String keyword) {
        adminProducts.expectFirstRowNameContains(keyword);
    }
}
