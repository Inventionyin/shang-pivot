package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 订单详情 `/orders/{id}`。 */
public final class MallOrderDetailPage extends BasePage {

    public MallOrderDetailPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openOrder(String orderId) {
        open("/orders/" + orderId);
        waitVisible(page().locator("h1.page-title").filter(
                new Locator.FilterOptions().setHasText("订单详情")), 15_000);
    }

    public void expectRawStatus(String status) {
        assertThat(page().locator(".panel").last()).containsText(status);
    }

    public void cancelOrder() {
        page().getByText("取消订单", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)).first().click();
        Locator dialog = page().locator(".van-dialog");
        waitVisible(dialog, 10_000);
        dialog.getByText("确认", new Locator.GetByTextOptions().setExact(true)).click();
    }

    public void expectClosed() {
        waitVisible(page().locator(".panel").filter(
                new Locator.FilterOptions().setHasText("CLOSED")).last(), 15_000);
    }
}
