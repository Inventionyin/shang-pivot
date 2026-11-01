package com.shangpivot.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.ui.BrowserSession;
import com.shangpivot.automation.ui.pages.MallCartPage;
import com.shangpivot.automation.ui.pages.MallCheckoutPage;
import com.shangpivot.automation.ui.pages.MallOrderDetailPage;
import com.shangpivot.automation.ui.pages.MallPayPage;
import com.shangpivot.automation.ui.pages.MallProductPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MallCheckoutUiSteps {

    private static final Pattern PAY_URL = Pattern.compile(".*/pay/(\\d+).*");

    private final ScenarioContext ctx;

    public MallCheckoutUiSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private BrowserSession session() {
        BrowserSession session = BrowserSession.current();
        Assertions.assertThat(session).as("UI 场景未启动浏览器").isNotNull();
        return session;
    }

    private void injectCustomerToken() {
        session().injectLocalStorage("shang_mall_access", ctx.customerToken());
    }

    @Given("我已通过接口登录商城并注入登录态")
    public void injectLoggedInState() {
        injectCustomerToken();
    }

    @Given("我通过接口准备默认收货地址")
    public void prepareAddress() {
        ApiResult list = ctx.address().list().assertSuccess();
        if (list.data().isEmpty()) {
            String id = ctx.address().create("自动化测试", ctx.phone(),
                    "北京市", "北京市", "朝阳区", "自动化路 1 号", true).assertSuccess()
                    .data().asText();
            ctx.registry().registerAddress(id);
        }
    }

    @When("我在商品详情页把商品加入购物车")
    public void addProductViaUi() {
        MallProductPage product = new MallProductPage(session());
        product.openProduct("101");
        ctx.productTitle(product.title());
        product.addCurrentProductToCart();

        ApiResult cart = ctx.cart().getCart().assertSuccess();
        for (JsonNode item : cart.data().path("items")) {
            if ("101".equals(item.path("spuId").asText())) {
                ctx.registry().registerCartItem(item.path("cartItemId").asText());
            }
        }
    }

    @Then("购物车应包含该商品")
    public void assertCartContains() {
        MallCartPage cart = new MallCartPage(session());
        cart.openCart();
        cart.expectItemPresent(ctx.productTitle());
    }

    @When("我取消购物车所有勾选并点击去结算")
    public void uncheckAndCheckout() {
        MallCartPage cart = new MallCartPage(session());
        cart.openCart();
        cart.uncheckAll();
        cart.clickCheckout();
    }

    @Then("应提示请先勾选商品且停留在购物车")
    public void assertSelectionBlocked() {
        new MallCartPage(session()).expectSelectionBlocked();
    }

    @When("我勾选商品并进入结算页")
    public void selectAndGotoCheckout() {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        for (JsonNode item : cart.data().path("items")) {
            if (item.path("title").asText().contains(ctx.productTitle())) {
                ctx.cart().updateSelected(item.path("cartItemId").asText(), true).assertSuccess();
            }
        }
        MallCartPage cartPage = new MallCartPage(session());
        cartPage.openCart();
        cartPage.clickCheckout();
        new MallCheckoutPage(session()).expectOnCheckout();
    }

    @Then("结算页应展示已选地址、商品清单和应付金额")
    public void assertCheckout() {
        MallCheckoutPage checkout = new MallCheckoutPage(session());
        checkout.expectAddressSelected();
        Assertions.assertThat(checkout.goodsLineCount()).as("结算页商品行数").isPositive();
        String payable = checkout.payableAmount().replace("¥", "").replace("￥", "")
                .replace(",", "").trim();
        Assertions.assertThat(payable).as("应付金额").matches("\\d+(\\.\\d+)?");
        Assertions.assertThat(Double.parseDouble(payable)).as("应付金额应大于 0").isPositive();
    }

    @When("我提交订单并完成沙箱支付")
    public void submitAndPay() {
        MallCheckoutPage checkout = new MallCheckoutPage(session());
        checkout.submitOrderAndConfirm();
        checkout.expectRedirectToPay();

        Matcher matcher = PAY_URL.matcher(session().page().url());
        Assertions.assertThat(matcher.matches()).as("支付页 URL 未包含订单号: %s", session().page().url()).isTrue();
        String orderId = matcher.group(1);
        ctx.lastOrderId(orderId);
        ctx.registry().registerOrder(orderId);

        new MallPayPage(session()).expectPaySuccess();
    }

    @Then("通过接口查询订单状态应为 {string}")
    public void assertOrderStatusViaApi(String status) {
        Assertions.assertThat(ctx.lastOrderId()).as("未取得订单号").isNotBlank();
        Assertions.assertThat(ctx.orderStatus(ctx.lastOrderId())).isEqualTo(status);
    }

    @When("我在订单详情页取消该订单")
    public void cancelOrderViaUi() {
        MallOrderDetailPage detail = new MallOrderDetailPage(session());
        detail.openOrder(ctx.lastOrderId());
        detail.cancelOrder();
    }

    @Then("订单详情应显示已关闭")
    public void assertOrderClosed() {
        new MallOrderDetailPage(session()).expectClosed();
        Assertions.assertThat(ctx.orderStatus(ctx.lastOrderId())).isEqualTo("CLOSED");
    }
}
