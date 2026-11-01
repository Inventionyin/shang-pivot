package com.shangpivot.automation.steps;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class SecuritySteps {

    private final ScenarioContext ctx;
    private int lastStatus;
    private Map<String, List<String>> lastHeaders;
    private String lastBody;

    public SecuritySteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    private String tokenVariant(String type) {
        String token = ctx.customerToken();
        return switch (type) {
            case "无 token" -> null;
            case "空 token" -> "";
            case "篡改签名" -> token.substring(0, token.length() - 1)
                    + (token.endsWith("a") ? "b" : "a");
            case "alg=none" -> {
                String[] parts = token.split("\\.");
                String header = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
                yield header + "." + parts[1] + ".";
            }
            default -> token;
        };
    }

    @Given("我有一个有效会员 token")
    public void validToken() {
        Assertions.assertThat(ctx.customerToken()).isNotBlank();
    }

    @When("我使用 {string} 访问购物车")
    public void accessCartWithToken(String type) {
        ctx.lastResult(ctx.rest().get("/api/cart", tokenVariant(type)));
    }

    @Then("令牌接口 HTTP 状态应为 {int}")
    public void assertTokenHttp(int status) {
        ctx.lastResult().assertHttpStatus(status);
    }

    @Then("响应不应包含用户信息")
    public void assertNoUserInfo() {
        String raw = ctx.lastResult().raw().toLowerCase();
        Assertions.assertThat(raw).doesNotContain("nickname", "phone", "customer");
    }

    @Then("响应不应包含 SQL 错误")
    public void assertNoSqlError() {
        String raw = ctx.lastResult().raw().toLowerCase();
        Assertions.assertThat(raw).doesNotContain("sql", "syntax", "sqlexception", "exception");
    }

    @When("我发送来自恶意 Origin 的预检请求")
    public void evilCorsPreflight() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestConfig.API_BASE_URL + "/api/home"))
                .timeout(Duration.ofSeconds(10))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://evil.com")
                .header("Access-Control-Request-Method", "GET")
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        lastStatus = response.statusCode();
        lastHeaders = response.headers().map();
        lastBody = response.body();
    }

    @Then("预检请求应被拒绝")
    public void assertCorsRejected() {
        Assertions.assertThat(lastStatus).as("恶意 Origin 预检应被拒绝").isIn(400, 401, 403);
        List<String> allowOrigin = lastHeaders.getOrDefault("access-control-allow-origin", List.of());
        Assertions.assertThat(allowOrigin).as("不应允许恶意 Origin").doesNotContain("http://evil.com", "*");
    }

    @When("我匿名请求首页并检查安全响应头")
    public void checkSecurityHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestConfig.API_BASE_URL + "/api/home"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        lastStatus = response.statusCode();
        lastHeaders = response.headers().map();
        lastBody = response.body();
    }

    @Then("安全响应头应存在")
    public void assertSecurityHeaders() {
        Assertions.assertThat(lastStatus).isEqualTo(200);
        Assertions.assertThat(lastHeaders.getOrDefault("x-content-type-options", List.of()))
                .as("X-Content-Type-Options").contains("nosniff");
        Assertions.assertThat(lastHeaders.getOrDefault("x-frame-options", List.of()))
                .as("X-Frame-Options").contains("DENY");
        Assertions.assertThat(lastHeaders.getOrDefault("content-type", List.of()).toString())
                .as("Content-Type").contains("application/json");
    }

    @When("我查询会员资料")
    public void queryProfile() {
        ctx.lastResult(ctx.auth().me(ctx.customerToken()).assertSuccess());
    }

    @Then("资料响应不应包含密码或令牌")
    public void assertNoSecrets() {
        String raw = ctx.lastResult().raw().toLowerCase();
        Assertions.assertThat(raw).doesNotContain("password", "accesstoken", "refreshtoken");
    }
}
