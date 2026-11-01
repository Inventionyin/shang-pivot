package com.shangpivot.automation.steps;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.api.ApiResult;
import com.shangpivot.automation.core.data.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

public class ApiAuthSteps {

    private final ScenarioContext ctx;

    public ApiAuthSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    @When("我用新手机号和演示验证码登录")
    public void loginWithNewPhone() {
        ctx.phone(TestData.uniquePhone());
        ctx.lastResult(ctx.auth().loginCustomer(ctx.phone(), TestConfig.DEMO_SMS_CODE).assertSuccess());
    }

    @Then("登录应成功并返回双令牌")
    public void assertTokensReturned() {
        ApiResult result = ctx.lastResult();
        Assertions.assertThat(result.data().path("accessToken").asText()).isNotBlank();
        Assertions.assertThat(result.data().path("refreshToken").asText()).isNotBlank();
        Assertions.assertThat(result.data().path("expiresIn").asLong()).isPositive();
        ctx.customerToken(result.data().path("accessToken").asText());
    }

    @Then("我用该令牌查询个人资料应返回同一手机号")
    public void assertProfileMatches() {
        ApiResult me = ctx.auth().me(ctx.customerToken()).assertSuccess();
        String maskedPhone = me.data().path("phone").asText();
        String lastFour = ctx.phone().substring(ctx.phone().length() - 4);
        Assertions.assertThat(maskedPhone).as("个人资料手机号").endsWith(lastFour);
        Assertions.assertThat(me.data().path("status").asText()).isEqualTo("NORMAL");
    }

    @When("我用演示会员手机号和错误验证码登录")
    public void loginWithWrongCode() {
        ctx.lastResult(ctx.auth().loginCustomer("13800000001", "000000"));
    }

    @Then("登录业务码应为 {int} 且不返回令牌")
    public void assertLoginCodeAndNoToken(int code) {
        ApiResult result = ctx.lastResult().assertCode(code);
        Assertions.assertThat(result.data().path("accessToken").asText()).isBlank();
    }

    @When("我用冻结会员手机号登录")
    public void loginFrozenCustomer() {
        ctx.lastResult(ctx.auth().loginCustomer("13500000005", TestConfig.DEMO_SMS_CODE));
    }

    @Then("登录业务码应为 {int}")
    public void assertLoginCode(int code) {
        ctx.lastResult().assertCode(code);
    }

    @Then("业务提示应包含 {string}")
    public void assertMessageContains(String text) {
        ctx.lastResult().assertMessageContains(text);
    }
}
