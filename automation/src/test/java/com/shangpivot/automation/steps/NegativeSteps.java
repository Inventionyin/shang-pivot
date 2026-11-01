package com.shangpivot.automation.steps;

import com.shangpivot.automation.api.AddressApi;
import com.shangpivot.automation.api.AuthApi;
import com.shangpivot.automation.api.OrderApi;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class NegativeSteps {

    private final ScenarioContext ctx;
    private String peerPhone;
    private String peerToken;
    private String peerAddressId;
    private String peerOrderId;

    public NegativeSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private String ensureAddress() {
        ctx.customerToken();
        ApiResult list = ctx.address().list().assertSuccess();
        if (!list.data().isEmpty()) {
            return list.data().get(0).path("id").asText();
        }
        ApiResult created = ctx.address().create("逆向测试", ctx.phone(),
                "北京市", "北京市", "朝阳区", "逆向路 1 号", true).assertSuccess();
        String id = created.data().asText();
        ctx.registry().registerAddress(id);
        return id;
    }

    @Given("用户 A 和用户 B 各自有一笔待支付订单")
    public void createTwoUsersWithOrders() {
        String ownAddress = ensureAddress();
        ApiResult ownOrder = ctx.order().submitDirect(ownAddress, "10026", 1, TestData.uniqueKey("peer-a")).assertSuccess();
        ctx.registry().registerOrder(ownOrder.data().path("orderId").asText());

        peerPhone = TestData.uniquePhone();
        RestClient peerClient = new RestClient(TestConfig.API_BASE_URL);
        AuthApi peerAuth = new AuthApi(peerClient);
        peerToken = peerAuth.loginCustomer(peerPhone).assertSuccess().data().path("accessToken").asText();
        AddressApi peerAddressApi = new AddressApi(peerClient, peerToken);
        peerAddressId = peerAddressApi.create("用户B", peerPhone,
                "上海市", "上海市", "浦东新区", "B 路 1 号", true).assertSuccess().data().asText();
        OrderApi peerOrderApi = new OrderApi(peerClient, peerToken);
        peerOrderId = peerOrderApi.submitDirect(peerAddressId, "10026", 1,
                TestData.uniqueKey("peer-b")).assertSuccess().data().path("orderId").asText();

        ctx.addCleanup(() -> {
            peerOrderApi.cancel(peerOrderId);
            peerAddressApi.delete(peerAddressId);
        });
    }

    @When("用户 A 使用自己的 token 请求用户 B 的订单详情")
    public void accessPeerOrder() {
        ctx.lastResult(ctx.order().detail(peerOrderId));
    }

    @Then("订单越权访问应被拒绝")
    public void assertOrderIdor() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.code()).as("越权访问订单应返回 403/404，实际 msg=%s", result.message())
                .isIn(40300, 40400);
        Assertions.assertThat(result.raw()).doesNotContain(peerPhone);
    }

    @Then("响应不应包含用户 B 的手机号")
    public void assertNoPeerPhone() {
        Assertions.assertThat(ctx.lastResult().raw()).doesNotContain(peerPhone);
    }

    @When("用户 A 尝试修改用户 B 的收货地址")
    public void updatePeerAddress() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "HACK");
        body.put("phone", peerPhone);
        body.put("province", "x");
        body.put("city", "x");
        body.put("district", "x");
        body.put("detail", "x");
        ctx.lastResult(ctx.rest().put("/api/addresses/" + peerAddressId, body, ctx.customerToken()));
    }

    @Then("地址越权访问应被拒绝")
    public void assertAddressIdor() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.code()).as("越权修改地址应返回 403/404，实际 msg=%s", result.message())
                .isIn(40300, 40400);
    }

    @When("我使用管理员 token 访问会员资料接口")
    public void adminTokenOnCustomerApi() {
        ctx.lastResult(ctx.rest().get("/api/me", ctx.adminToken()));
    }

    @Then("会员接口 HTTP 状态应为 {int}")
    public void assertCustomerApiHttp(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }

    @When("我提交包含 status 字段的资料更新请求")
    public void massAssignment() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", "sec-test");
        body.put("status", "FROZEN");
        ctx.lastResult(ctx.rest().put("/api/me", body, ctx.customerToken()));
    }

    @Then("会员状态应保持 {string}")
    public void assertStatusUnchanged(String expected) {
        ApiResult me = ctx.auth().me(ctx.customerToken()).assertSuccess();
        Assertions.assertThat(me.data().path("status").asText()).isEqualTo(expected);
    }

    @When("我提交一笔金额被篡改的订单")
    public void priceTampering() {
        String addressId = ensureAddress();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("price", new BigDecimal("0.01"));
        extras.put("payAmount", new BigDecimal("0.01"));
        extras.put("freight", BigDecimal.ZERO);
        ApiResult result = ctx.order().submitDirectWithExtras(addressId, "10026", 1,
                TestData.uniqueKey("price-tamper"), extras).assertSuccess();
        ctx.lastResult(result);
        ctx.registry().registerOrder(result.data().path("orderId").asText());
    }

    @Then("订单应付金额应为 {string}")
    public void assertPayAmount(String expected) {
        Assertions.assertThat(ctx.lastResult().data().path("payAmount").decimalValue())
                .as("客户端篡改金额应被忽略")
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @When("我使用不存在的订单号查询订单")
    public void queryNonexistentOrder() {
        ctx.lastResult(ctx.order().detail("999999999999999999"));
    }

    @Then("订单查询应返回不存在")
    public void assertOrderNotFound() {
        ctx.lastResult().assertCode(40400);
        Assertions.assertThat(ctx.lastResult().raw().toLowerCase())
                .doesNotContain("phone", "address", "nickname");
    }

    @Then("响应不应包含敏感字段 {string}")
    public void assertNoSensitiveField(String field) {
        Assertions.assertThat(ctx.lastResult().raw().toLowerCase()).doesNotContain(field.toLowerCase());
    }
}
