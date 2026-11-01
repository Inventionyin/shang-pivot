package com.shangpivot.automation.steps;

import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.SchemaValidator;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;

public class ApiOrderPaymentSteps {

    private final ScenarioContext ctx;
    private String orderId;
    private String addressId;
    private String skuId;
    private int quantity;
    private String idempotentKey;

    public ApiOrderPaymentSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @When("我准备一个含 SKU {string} 数量 {int} 的待支付订单")
    public void preparePendingOrder(String sku, int qty) {
        ctx.customerToken();
        addressId = ensureAddress();
        skuId = sku;
        quantity = qty;
        idempotentKey = TestData.uniqueKey("order");
        ApiResult result = ctx.order().submitDirect(addressId, skuId, quantity, idempotentKey).assertSuccess();
        orderId = result.data().path("orderId").asText();
        Assertions.assertThat(orderId).as("下单 orderId").isNotBlank();
        ctx.lastOrderId(orderId);
        ctx.lastOrderNo(result.data().path("orderNo").asText());
        ctx.registry().registerOrder(orderId);
        ctx.lastResult(result);
    }

    @Then("订单运费应为 {double} 且状态为 PENDING_PAY")
    public void assertFreight(double freight) {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("freight").decimalValue())
                .isEqualByComparingTo(BigDecimal.valueOf(freight));
        Assertions.assertThat(result.data().path("status").asText()).isEqualTo("PENDING_PAY");
        Assertions.assertThat(result.data().path("payAmount").decimalValue())
                .isEqualByComparingTo(result.data().path("goodsAmount").decimalValue()
                        .add(BigDecimal.valueOf(freight)));
        SchemaValidator.validate(result.data(), "schemas/order-submit.json");
    }

    @Then("我用相同幂等键再次提交应返回同一订单")
    public void assertIdempotent() {
        ApiResult again = ctx.order().submitDirect(addressId, skuId, quantity, idempotentKey).assertSuccess();
        Assertions.assertThat(again.data().path("orderId").asText())
                .as("相同幂等键应返回同一订单")
                .isEqualTo(orderId);
        Assertions.assertThat(again.data().path("orderNo").asText())
                .isEqualTo(ctx.lastOrderNo());
    }

    @When("我对该订单发起沙箱支付")
    public void payOrder() {
        ctx.lastResult(ctx.payment().sandboxPay(orderId).assertSuccess());
    }

    @Then("订单状态应变为 PENDING_SHIP 且交易号以 SANDBOX- 开头")
    public void assertPaid() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("status").asText()).isEqualTo("PENDING_SHIP");
        Assertions.assertThat(result.data().path("tradeNo").asText()).startsWith("SANDBOX-" + orderId);
        Assertions.assertThat(ctx.orderStatus(orderId)).isEqualTo("PENDING_SHIP");
    }

    @When("我再次支付同一订单应失败")
    public void payAgain() {
        ctx.lastResult(ctx.payment().sandboxPay(orderId));
    }

    @Then("支付业务码应为 {int}")
    public void assertPayCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @When("我取消该订单")
    public void cancelOrder() {
        ctx.order().cancel(orderId).assertSuccess();
    }

    @Then("订单状态应为 {string}")
    public void assertOrderStatus(String status) {
        Assertions.assertThat(ctx.orderStatus(orderId)).isEqualTo(status);
    }

    @When("我取消该已支付订单应失败")
    public void cancelPaidOrder() {
        ctx.lastResult(ctx.order().cancel(orderId));
    }

    @Then("取消失败业务码应为 {int}")
    public void assertCancelCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @When("我未登录访问订单列表")
    public void requestOrdersWithoutToken() {
        ctx.lastResult(ctx.rest().get("/api/orders", null));
    }

    @Then("订单接口 HTTP 状态应为 {int}")
    public void assertOrderHttpStatus(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }

    public String currentOrderId() {
        return orderId;
    }

    private String ensureAddress() {
        ApiResult list = ctx.address().list().assertSuccess();
        for (var node : list.data()) {
            if (node.path("isDefault").asBoolean(false)) {
                return node.path("id").asText();
            }
        }
        if (list.data().size() > 0) {
            return list.data().get(0).path("id").asText();
        }
        ApiResult created = ctx.address().create("自动化测试", ctx.phone(),
                "北京市", "北京市", "朝阳区", "自动化路 1 号", true).assertSuccess();
        String id = created.data().asText();
        ctx.registry().registerAddress(id);
        return id;
    }
}
