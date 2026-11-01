package com.shangpivot.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

public class ApiAdminSteps {

    private final ScenarioContext ctx;
    private String trackingNo;

    public ApiAdminSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @When("我以管理员身份登录")
    public void loginAdmin() {
        Assertions.assertThat(ctx.adminToken()).isNotBlank();
    }

    @Then("管理看板应返回数字指标且商品数大于 0")
    public void assertDashboard() {
        ApiResult stats = ctx.admin().stats().assertSuccess();
        JsonNode data = stats.data();
        Assertions.assertThat(data.path("onShelfProducts").asLong()).as("在售商品数").isPositive();
        for (String field : new String[]{"pendingShipOrders", "onShelfProducts", "skuTotal", "pendingAfterSales", "lowStockCount"}) {
            // 后端把 Long 统一序列化为字符串，这里同时接受数字与数字字符串
            Assertions.assertThat(data.path(field).asText())
                    .as("看板字段 %s 应为数字，实际=%s", field, data.path(field))
                    .matches("\\d+");
        }
    }

    @When("我按关键字 {string} 查询后台商品")
    public void searchAdminProducts(String keyword) {
        ctx.lastResult(ctx.admin().products(1, 10, keyword).assertSuccess());
    }

    @Then("后台商品结果应全部包含关键字")
    public void assertAdminProductRows() {
        ApiResult result = ctx.lastResult();
        JsonNode records = result.data().path("records");
        Assertions.assertThat(records.size()).as("后台商品结果").isPositive();
        for (JsonNode record : records) {
            Assertions.assertThat(record.path("name").asText()).contains("iPhone");
        }
        Assertions.assertThat(result.data().path("total").asLong()).isPositive();
    }

    @When("我把该待支付订单支付并发货 {string}")
    public void payAndShip(String carrier) {
        String orderId = ctx.lastOrderId();
        Assertions.assertThat(orderId).as("缺少待支付订单").isNotBlank();
        ctx.payment().sandboxPay(orderId).assertSuccess();
        trackingNo = TestData.uniqueKey("SF");
        ctx.admin().ship(orderId, trackingNo, carrier).assertSuccess();
    }

    @Then("订单状态应变为 {string}")
    public void assertOrderStatus(String status) {
        Assertions.assertThat(ctx.orderStatus(ctx.lastOrderId())).isEqualTo(status);
    }

    @Then("管理端订单详情应包含该运单号")
    public void assertShipment() {
        ApiResult detail = ctx.admin().orderDetail(ctx.lastOrderId()).assertSuccess();
        boolean found = false;
        for (JsonNode shipment : detail.data().path("shipments")) {
            if (trackingNo.equals(shipment.path("trackingNo").asText())) {
                found = true;
                break;
            }
        }
        Assertions.assertThat(found).as("管理端订单详情应包含运单号 %s", trackingNo).isTrue();
    }

    @When("我用会员令牌访问管理端订单")
    public void customerTokenOnAdmin() {
        ctx.lastResult(ctx.rest().get("/admin/orders?page=1&size=1", ctx.customerToken()));
    }

    @Then("管理端 HTTP 状态应为 {int}")
    public void assertAdminHttpStatus(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }
}
