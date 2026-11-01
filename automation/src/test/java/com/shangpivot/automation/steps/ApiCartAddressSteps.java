package com.shangpivot.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;

public class ApiCartAddressSteps {

    private final ScenarioContext ctx;
    private String cartItemId;
    private String firstAddressId;
    private String secondAddressId;

    public ApiCartAddressSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @Given("我用新会员身份登录")
    public void loginNewCustomer() {
        ctx.customerToken();
    }

    @When("我向购物车加入 SKU {string} 数量 {int}")
    public void addCartItem(String skuId, int quantity) {
        ApiResult result = ctx.cart().add(skuId, quantity).assertSuccess();
        cartItemId = result.data().path("cartItemId").asText();
        Assertions.assertThat(cartItemId).as("加购响应 cartItemId").isNotBlank();
        ctx.registry().registerCartItem(cartItemId);
        ctx.lastResult(result);
    }

    @Then("购物车应包含该商品且金额等于单价乘数量")
    public void assertCartAmount() {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        JsonNode item = findCartItem(cart, cartItemId);
        Assertions.assertThat(item.isMissingNode()).as("购物车应包含刚加入的商品").isFalse();
        Assertions.assertThat(item.path("selected").asBoolean()).isTrue();
        BigDecimal expected = item.path("price").decimalValue()
                .multiply(BigDecimal.valueOf(item.path("quantity").asInt()));
        BigDecimal actual = cart.data().path("selectedAmount").decimalValue();
        Assertions.assertThat(actual).as("已选金额").isEqualByComparingTo(expected);
        ctx.lastResult(cart);
    }

    @When("我把该购物车项数量改为 {int}")
    public void updateQuantity(int quantity) {
        ctx.cart().updateQuantity(cartItemId, quantity).assertSuccess();
    }

    @Then("购物车金额应按新数量更新")
    public void assertUpdatedAmount() {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        JsonNode item = findCartItem(cart, cartItemId);
        Assertions.assertThat(item.path("quantity").asInt()).isEqualTo(3);
        BigDecimal expected = item.path("price").decimalValue().multiply(BigDecimal.valueOf(3));
        Assertions.assertThat(cart.data().path("selectedAmount").decimalValue())
                .isEqualByComparingTo(expected);
        ctx.lastResult(cart);
    }

    @When("我取消该购物车项的勾选")
    public void unselectItem() {
        ctx.cart().updateSelected(cartItemId, false).assertSuccess();
    }

    @Then("购物车已选金额应为 0")
    public void assertSelectedAmountZero() {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        Assertions.assertThat(cart.data().path("selectedAmount").decimalValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
        Assertions.assertThat(findCartItem(cart, cartItemId).path("selected").asBoolean()).isFalse();
    }

    @When("我删除该购物车项")
    public void removeItem() {
        ctx.cart().remove(cartItemId).assertSuccess();
    }

    @Then("购物车应不再包含该商品")
    public void assertItemGone() {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        Assertions.assertThat(findCartItem(cart, cartItemId).isMissingNode()).isTrue();
    }

    @When("我向购物车加入 SKU {string} 数量 {int} 应失败")
    public void addTooMany(String skuId, int quantity) {
        ctx.lastResult(ctx.cart().add(skuId, quantity));
    }

    @Then("购物车业务码应为 {int}")
    public void assertCartCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @When("我创建一个默认收货地址")
    public void createDefaultAddress() {
        ApiResult result = ctx.address().create("自动化测试", ctx.phone(),
                "北京市", "北京市", "朝阳区", "自动化路 1 号", true).assertSuccess();
        firstAddressId = result.data().asText();
        Assertions.assertThat(firstAddressId).isNotBlank();
        ctx.registry().registerAddress(firstAddressId);
    }

    @Then("收货地址列表应包含该地址且为默认")
    public void assertAddressDefault() {
        JsonNode address = findAddress(firstAddressId);
        Assertions.assertThat(address.isMissingNode()).isFalse();
        Assertions.assertThat(address.path("isDefault").asBoolean()).isTrue();
    }

    @When("我再创建一个收货地址并设为默认")
    public void createSecondAndSetDefault() {
        ApiResult created = ctx.address().create("自动化测试二", ctx.phone(),
                "上海市", "上海市", "浦东新区", "自动化路 2 号", false).assertSuccess();
        secondAddressId = created.data().asText();
        ctx.registry().registerAddress(secondAddressId);
        ctx.address().setDefault(secondAddressId).assertSuccess();
    }

    @Then("原地址应不再是默认地址")
    public void assertDefaultSwitched() {
        Assertions.assertThat(findAddress(firstAddressId).path("isDefault").asBoolean()).isFalse();
        Assertions.assertThat(findAddress(secondAddressId).path("isDefault").asBoolean()).isTrue();
    }

    @When("我未登录访问购物车")
    public void requestCartWithoutToken() {
        ctx.lastResult(ctx.rest().get("/api/cart", null));
    }

    @Then("购物车接口 HTTP 状态应为 {int}")
    public void assertCartHttpStatus(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }

    private JsonNode findCartItem(String id) {
        return findCartItem(ctx.cart().getCart().assertSuccess(), id);
    }

    private JsonNode findCartItem(ApiResult cart, String id) {
        for (JsonNode item : cart.data().path("items")) {
            if (id.equals(item.path("cartItemId").asText()) || id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private JsonNode findAddress(String id) {
        ApiResult list = ctx.address().list().assertSuccess();
        for (JsonNode address : list.data()) {
            if (id.equals(address.path("id").asText())) {
                return address;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
