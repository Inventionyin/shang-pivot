package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** C 端 AI 助手页（/ai）：发送、商品卡、历史恢复、新对话。 */
public final class MallAiChatPage extends BasePage {

    private static final int AI_TIMEOUT_MS = 120_000;

    public MallAiChatPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    /** 从商城首页右侧 AI 快捷栏进入（模拟真实站内入口，带路由守卫许可）。 */
    public void openFromHome() {
        open("/");
        waitVisible(page().locator(".rail-ai"), 20_000);
        page().locator(".rail-ai").click();
        expectPath("/ai");
        waitVisible(page().getByTestId("ai-chat"), 20_000);
        waitForFirstBubble();
    }

    /** 平台人工体验入口：显式允许直接打开 /ai。 */
    public void openHumanTest() {
        open("/ai?test=human");
        expectPath("/ai");
        waitVisible(page().getByTestId("ai-chat"), 20_000);
        waitForFirstBubble();
    }

    /** H5 形态：从首页悬浮球进入，验证移动端返回栈。 */
    public void openFromHomeH5() {
        page().setViewportSize(390, 844);
        open("/");
        waitVisible(page().locator(".h5-ai-orb"), 20_000);
        page().locator(".h5-ai-orb").click();
        expectPath("/ai");
        waitVisible(page().getByTestId("ai-chat"), 20_000);
        waitForFirstBubble();
    }

    public void sendAndWaitAnswer(String text) {
        int before = page().getByTestId("ai-chat-bubble").count();
        Locator input = page().getByTestId("ai-chat-input");
        input.fill(text);
        page().getByTestId("ai-chat-send").click();
        // 等新助手气泡出现并结束流式（data-streaming=false）
        page().waitForFunction(
                "() => {"
                        + " const rows = [...document.querySelectorAll('[data-testid=ai-chat-bubble]')];"
                        + " return rows.length > " + before
                        + " && rows[rows.length - 1].getAttribute('data-streaming') === 'false';"
                        + " }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(AI_TIMEOUT_MS));
    }

    public void expectCardVisible() {
        assertThat(page().getByTestId("ai-chat-card").first()).isVisible();
    }

    public void expectActionCardVisible() {
        assertThat(page().locator(".action-card").last()).isVisible();
        assertThat(page().locator(".action-card__summary").last()).containsText("加入购物车");
    }

    public void expectMessagesAtBottom() {
        String expression = "() => { const el = document.querySelector('[data-testid=ai-chat-messages]');"
                + " return !!el && el.scrollHeight - el.scrollTop - el.clientHeight < 40; }";
        try {
            page().waitForFunction(expression, null,
                    new Page.WaitForFunctionOptions().setTimeout(5_000));
        } catch (com.microsoft.playwright.TimeoutError ignored) {
            // 下面统一读取实际值给出可诊断的失败信息。
        }
        Object raw = page().evaluate(
                "() => { const el = document.querySelector('[data-testid=ai-chat-messages]');"
                        + " return el ? {top: el.scrollTop, client: el.clientHeight, height: el.scrollHeight} : null; }");
        if (!(raw instanceof java.util.Map<?, ?> map)) {
            throw new AssertionError("找不到 AI 消息区");
        }
        double top = ((Number) map.get("top")).doubleValue();
        double client = ((Number) map.get("client")).doubleValue();
        double height = ((Number) map.get("height")).doubleValue();
        if (top + client < height - 40) {
            throw new AssertionError("AI 消息区未滚动到底部：top=" + top
                    + " client=" + client + " height=" + height);
        }
    }

    public void clickFirstCard() {
        page().getByTestId("ai-chat-card").first().locator(".card__main").click();
        expectPath("/product/");
        waitVisible(page().locator("h1.page-title"), 30_000);
    }

    /** 点击第一张商品卡的“加入购物车”按钮（不离开 /ai）。 */
    public void clickFirstCardAddToCart() {
        page().getByTestId("ai-chat-add-cart").first().click();
    }

    public void goBackToAi() {
        page().goBack();
        expectPath("/ai");
        waitVisible(page().getByTestId("ai-chat"), 20_000);
        waitForFirstBubble();
    }

    public void reloadAi() {
        page().reload();
        expectPath("/ai");
        waitVisible(page().getByTestId("ai-chat"), 20_000);
        waitForFirstBubble();
    }

    public void expectBubbleContains(String text) {
        assertThat(page().getByTestId("ai-chat-messages")).containsText(text);
    }

    public void expectBubbleNotContains(String text) {
        assertThat(page().getByTestId("ai-chat-messages")).not().containsText(text);
    }

    public void startNewConversation() {
        page().getByTestId("ai-chat-new").click();
        // 新会话只有一条欢迎语；等待历史气泡退出且最后一条为欢迎语。
        page().waitForFunction(
                "() => {"
                        + " const rows = [...document.querySelectorAll('[data-testid=ai-chat-bubble]')];"
                        + " return rows.length === 1 && rows[0].textContent.includes('小尚');"
                        + " }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    public void openHistory() {
        page().getByTestId("ai-chat-history").click();
        waitVisible(page().locator(".history__item").first(), 20_000);
    }

    public void expectHistoryContains(String text) {
        assertThat(page().locator(".history")).containsText(text);
    }

    public void clickHistoryFirst() {
        page().locator(".history__item").first().click();
        waitForFirstBubble();
    }

    private void waitForFirstBubble() {
        waitVisible(page().getByTestId("ai-chat-bubble").first(), 30_000);
    }
}
