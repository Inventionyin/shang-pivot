package com.shangpivot.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;

public class BoundarySteps {

    private final ScenarioContext ctx;
    private String skuId;
    private int skuStock;

    public BoundarySteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @When("我查询 SPU {string} 中 SKU {string} 的可售库存")
    public void querySkuStock(String spuId, String targetSkuId) {
        ApiResult detail = ctx.catalog().product(spuId).assertSuccess();
        for (JsonNode sku : detail.data().path("skus")) {
            if (targetSkuId.equals(sku.path("skuId").asText())) {
                skuId = targetSkuId;
                skuStock = sku.path("availableStock").asInt();
                Assertions.assertThat(skuStock).as("SKU 可售库存").isGreaterThan(0);
                return;
            }
        }
        throw new AssertionError("SPU " + spuId + " 下找不到 SKU " + targetSkuId);
    }

    @When("我向购物车加入该 SKU 数量为库存")
    public void addStockQuantity() {
        ApiResult result = ctx.cart().add(skuId, skuStock).assertSuccess();
        ctx.lastResult(result);
        ctx.registry().registerCartItem(result.data().path("cartItemId").asText());
    }

    @When("我向购物车加入该 SKU 数量为库存加 {int}")
    public void addStockPlus(int extra) {
        ctx.lastResult(ctx.cart().add(skuId, skuStock + extra));
    }

    @When("我尝试向购物车加入 SKU {string} 数量 {int}")
    public void tryAddCart(String targetSkuId, int quantity) {
        skuId = targetSkuId;
        ctx.lastResult(ctx.cart().add(targetSkuId, quantity));
    }

    @When("我向购物车加入 SKU {string} 数量原始值 {string}")
    public void addRawQuantity(String targetSkuId, String raw) {
        Object quantity;
        if (raw.matches("-?\\d+")) {
            quantity = Integer.parseInt(raw);
        } else if (raw.matches("-?\\d+\\.\\d+")) {
            quantity = new BigDecimal(raw);
        } else {
            quantity = raw.replace("\"", "");
        }
        ctx.lastResult(ctx.cart().addRaw(targetSkuId, quantity));
    }

    @Then("购物车中该商品数量应为 {int}")
    public void assertCartQuantity(int expected) {
        ApiResult cart = ctx.cart().getCart().assertSuccess();
        int actual = 0;
        for (JsonNode item : cart.data().path("items")) {
            if (skuId != null && skuId.equals(item.path("skuId").asText())) {
                actual = item.path("quantity").asInt();
            }
        }
        Assertions.assertThat(actual).as("购物车中 SKU 数量").isEqualTo(expected);
    }

    @When("我匿名请求商品分页 page={int} size={int}")
    public void requestProductsPage(int page, int size) {
        ctx.lastResult(ctx.catalog().products(page, size, "comp"));
    }

    @Then("分页业务码应为 {int}")
    public void assertPageCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @Then("分页返回记录数应不超过 {int}")
    public void assertRecords(int max) {
        Assertions.assertThat(ctx.lastResult().data().path("records").size()).isLessThanOrEqualTo(max);
    }

    @When("我用手机号 {string} 和验证码 {string} 登录")
    public void loginWithPhone(String phone, String code) {
        ctx.lastResult(ctx.auth().loginCustomer(phone, code));
    }

    @Then("登录接口 HTTP 状态应为 {int}")
    public void assertLoginHttp(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }

    @When("我用新手机号和验证码 {string} 登录")
    public void loginWithCode(String code) {
        ctx.phone(TestData.uniquePhone());
        ctx.lastResult(ctx.auth().loginCustomer(ctx.phone(), code));
    }

    @When("我创建 20 条收货地址")
    public void createTwentyAddresses() {
        ctx.customerToken();
        for (int i = 1; i <= 20; i++) {
            ApiResult created = ctx.address().create("边界测试" + i, ctx.phone(),
                    "北京市", "北京市", "朝阳区", "边界路 " + i + " 号", i == 1).assertSuccess();
            ctx.registry().registerAddress(created.data().asText());
        }
    }

    @Then("第 21 条收货地址创建应失败")
    public void createTwentyFirst() {
        ctx.lastResult(ctx.address().create("边界测试21", ctx.phone(),
                "北京市", "北京市", "朝阳区", "边界路 21 号", false));
        ctx.lastResult().assertCode(40000);
    }
}
