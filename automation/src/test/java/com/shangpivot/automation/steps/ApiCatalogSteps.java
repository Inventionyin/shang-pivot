package com.shangpivot.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.api.SchemaValidator;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ApiCatalogSteps {

    private final ScenarioContext ctx;

    public ApiCatalogSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @When("我匿名请求首页")
    public void requestHome() {
        ctx.lastResult(ctx.catalog().home(20).assertSuccess());
    }

    @Then("首页应返回非空商品流和运营位")
    public void assertHomeContent() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("feed").size()).as("首页商品流").isPositive();
        Assertions.assertThat(result.data().path("slots").size()).as("首页运营位").isPositive();
        List<String> codes = new ArrayList<>();
        result.data().path("slots").forEach(slot -> codes.add(slot.path("code").asText()));
        Assertions.assertThat(codes).contains("home_banner");
    }

    @Then("首页响应应符合契约")
    public void validateHomeSchema() {
        SchemaValidator.validate(ctx.lastResult().data(), "schemas/home.json");
    }

    @When("我匿名请求第 {int} 页商品并按价格升序")
    public void requestProductsSorted(int page) {
        ctx.lastResult(ctx.catalog().products(page, 10, "price_asc").assertSuccess());
    }

    @Then("商品总数应大于 0 且每页 {int} 条")
    public void assertProductPage(int size) {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("total").asLong()).isPositive();
        Assertions.assertThat(result.data().path("records").size()).isBetween(1, size);
    }

    @Then("商品价格应按升序排列")
    public void assertPriceAscending() {
        JsonNode records = ctx.lastResult().data().path("records");
        BigDecimal previous = BigDecimal.ZERO;
        for (JsonNode record : records) {
            BigDecimal price = record.path("price").decimalValue();
            Assertions.assertThat(price)
                    .as("价格应升序，当前 %s 小于前一个 %s", price, previous)
                    .isGreaterThanOrEqualTo(previous);
            previous = price;
        }
    }

    @When("我匿名请求商品 {string} 的详情")
    public void requestProductDetail(String spuId) {
        ctx.lastResult(ctx.catalog().product(spuId).assertSuccess());
    }

    @Then("详情应返回 SKU 列表且库存非负")
    public void assertProductDetail() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("spu").path("id").asText()).isNotBlank();
        Assertions.assertThat(result.data().path("spu").path("name").asText()).isNotBlank();
        JsonNode skus = result.data().path("skus");
        Assertions.assertThat(skus.size()).isPositive();
        for (JsonNode sku : skus) {
            Assertions.assertThat(sku.path("skuId").asText()).isNotBlank();
            Assertions.assertThat(sku.path("availableStock").asInt()).isGreaterThanOrEqualTo(0);
        }
    }

    @Then("详情响应应符合契约")
    public void validateProductSchema() {
        SchemaValidator.validate(ctx.lastResult().data(), "schemas/product-detail.json");
    }

    @When("我匿名搜索关键词 {string}")
    public void search(String keyword) {
        ctx.lastResult(ctx.catalog().search(keyword, 1, 10).assertSuccess());
    }

    @Then("搜索结果总数应 {string}")
    public void assertSearchTotal(String expectation) {
        long total = ctx.lastResult().data().path("total").asLong();
        if ("大于 0".equals(expectation)) {
            Assertions.assertThat(total).as("搜索命中数").isPositive();
        } else if ("等于 0".equals(expectation)) {
            Assertions.assertThat(total).as("搜索未命中数").isZero();
        } else {
            throw new IllegalArgumentException("不支持的总数期望: " + expectation);
        }
    }
}
