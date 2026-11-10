package com.shangpivot.automation.steps;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.ui.BrowserSession;
import com.shangpivot.automation.ui.pages.MallAiChatPage;
import com.shangpivot.automation.ui.pages.MallLoginPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.assertj.core.api.Assertions;

public class MallAiChatUiSteps {

    private final ScenarioContext ctx;

    public MallAiChatUiSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    private BrowserSession session() {
        BrowserSession session = BrowserSession.current();
        Assertions.assertThat(session).as("AI 会话 UI 场景未启动浏览器").isNotNull();
        return session;
    }

    private void loginNewCustomer() {
        MallLoginPage login = new MallLoginPage(session());
        login.login(ctx.phone(), TestConfig.DEMO_SMS_CODE);
        login.expectLoggedIn();
    }

    @Given("我用新手机号登录商城并打开 AI 助手")
    public void loginAndOpenAi() {
        loginNewCustomer();
        new MallAiChatPage(session()).openFromHome();
    }

    @Given("我用新手机号登录商城并打开人工体验 AI 助手")
    public void loginAndOpenHumanTestAi() {
        loginNewCustomer();
        new MallAiChatPage(session()).openHumanTest();
    }

    @Given("我用新手机号登录商城并通过 H5 悬浮球打开 AI 助手")
    public void loginAndOpenH5Ai() {
        loginNewCustomer();
        new MallAiChatPage(session()).openFromHomeH5();
    }

    @When("我在 AI 助手发送 {string}")
    public void sendInAi(String text) {
        new MallAiChatPage(session()).sendAndWaitAnswer(text);
    }

    @Then("AI 助手应展示商品卡片")
    public void expectAiCards() {
        new MallAiChatPage(session()).expectCardVisible();
    }

    @Then("AI 助手应显示待确认加购卡片")
    public void expectAiActionCard() {
        new MallAiChatPage(session()).expectActionCardVisible();
    }

    @Then("AI 助手消息区应滚动到底部")
    public void expectAiAtBottom() {
        new MallAiChatPage(session()).expectMessagesAtBottom();
    }

    @When("我点击第一张 AI 商品卡片")
    public void clickFirstAiCard() {
        new MallAiChatPage(session()).clickFirstCard();
    }

    @When("我点击第一张 AI 商品卡片的加入购物车")
    public void clickFirstAiCardAddToCart() {
        new MallAiChatPage(session()).clickFirstCardAddToCart();
    }

    @When("我点击第一个 AI 规格快捷选项")
    public void clickFirstSkuOption() {
        new MallAiChatPage(session()).clickFirstSkuOption();
    }

    @When("我返回 AI 助手页面")
    public void backToAi() {
        new MallAiChatPage(session()).goBackToAi();
    }

    @When("我刷新 AI 助手页面")
    public void reloadAi() {
        new MallAiChatPage(session()).reloadAi();
    }

    @Then("AI 助手对话应包含 {string}")
    public void expectAiBubble(String text) {
        new MallAiChatPage(session()).expectBubbleContains(text);
    }

    @Then("AI 助手对话不应包含 {string}")
    public void expectAiBubbleNotContains(String text) {
        new MallAiChatPage(session()).expectBubbleNotContains(text);
    }

    @When("我点击 AI 新对话")
    public void clickNewConversation() {
        new MallAiChatPage(session()).startNewConversation();
    }

    @When("我打开 AI 历史会话")
    public void openAiHistory() {
        new MallAiChatPage(session()).openHistory();
    }

    @Then("AI 历史会话应包含 {string}")
    public void expectAiHistory(String text) {
        new MallAiChatPage(session()).expectHistoryContains(text);
    }
}
