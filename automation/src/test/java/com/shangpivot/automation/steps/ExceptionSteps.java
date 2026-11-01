package com.shangpivot.automation.steps;

import com.shangpivot.automation.api.OrderApi;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.RestClient;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ExceptionSteps {

    private static final String SKU = "10026";
    private final ScenarioContext ctx;
    private List<ApiResult> concurrentResults;

    public ExceptionSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private String ensureAddress() {
        ctx.customerToken();
        ApiResult list = ctx.address().list().assertSuccess();
        if (!list.data().isEmpty()) {
            return list.data().get(0).path("id").asText();
        }
        ApiResult created = ctx.address().create("异常测试", ctx.phone(),
                "北京市", "北京市", "朝阳区", "异常路 1 号", true).assertSuccess();
        String id = created.data().asText();
        ctx.registry().registerAddress(id);
        return id;
    }

    @When("我发送格式错误的 JSON 登录请求")
    public void malformedJson() {
        ctx.lastResult(ctx.rest().postRaw("/api/auth/login", "{bad", null, "application/json"));
    }

    @When("我使用 Content-Type {string} 发送登录请求")
    public void wrongContentType(String contentType) {
        String body = "{\"phone\":\"13900000000\",\"code\":\"123456\",\"clientType\":\"web\"}";
        ctx.lastResult(ctx.rest().postRaw("/api/auth/login", body, null, contentType));
    }

    @Then("响应体不应包含堆栈信息")
    public void assertNoStackTrace() {
        String raw = ctx.lastResult().raw().toLowerCase();
        Assertions.assertThat(raw).doesNotContain("exception", "at com.", "stacktrace", "sqlexception");
    }

    @When("我提交缺少幂等键的订单")
    public void submitWithoutIdempotencyKey() {
        String addressId = ensureAddress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressId", addressId);
        body.put("skuId", SKU);
        body.put("quantity", 1);
        ctx.lastResult(ctx.rest().post("/api/orders", body, ctx.customerToken()));
    }

    @When("我提交 SKU {string} 不存在的订单")
    public void submitUnknownSku(String skuId) {
        String addressId = ensureAddress();
        ctx.lastResult(ctx.order().submitDirect(addressId, skuId, 1, TestData.uniqueKey("unknown-sku")));
    }

    @When("我尝试对该订单发起沙箱支付")
    public void tryPay() {
        ctx.lastResult(ctx.payment().sandboxPay(ctx.lastOrderId()));
    }

    @Then("下单业务码应为 {int}")
    public void assertOrderCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @When("我使用同一幂等键并发提交 2 次下单请求")
    public void concurrentSubmit() throws Exception {
        String addressId = ensureAddress();
        String key = TestData.uniqueKey("concurrent");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        String token = ctx.customerToken();
        try {
            Future<ApiResult> first = pool.submit(() -> {
                start.await();
                return new OrderApi(new RestClient(TestConfig.API_BASE_URL), token)
                        .submitDirect(addressId, SKU, 1, key);
            });
            Future<ApiResult> second = pool.submit(() -> {
                start.await();
                return new OrderApi(new RestClient(TestConfig.API_BASE_URL), token)
                        .submitDirect(addressId, SKU, 1, key);
            });
            start.countDown();
            ApiResult r1 = first.get(20, TimeUnit.SECONDS);
            ApiResult r2 = second.get(20, TimeUnit.SECONDS);
            concurrentResults = List.of(r1, r2);
            ctx.lastResult(r1);
            for (ApiResult result : concurrentResults) {
                String orderId = result.data().path("orderId").asText();
                if (!orderId.isBlank()) {
                    ctx.registry().registerOrder(orderId);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Then("两次提交应返回同一订单号")
    public void assertSameOrder() {
        Assertions.assertThat(concurrentResults).as("并发结果").isNotNull().hasSize(2);
        ApiResult first = concurrentResults.get(0);
        ApiResult second = concurrentResults.get(1);
        Assertions.assertThat(first.code())
                .as("第一次并发下单应成功，实际 code=%s msg=%s", first.code(), first.message())
                .isZero();
        Assertions.assertThat(second.code())
                .as("第二次并发下单应成功，实际 code=%s msg=%s", second.code(), second.message())
                .isZero();
        String firstId = first.data().path("orderId").asText();
        String secondId = second.data().path("orderId").asText();
        Assertions.assertThat(firstId).isNotBlank().isEqualTo(secondId);
    }
}
