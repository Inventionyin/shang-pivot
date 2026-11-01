package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 沙箱收银台 `/pay/{orderId}`：进入后自动发起支付。 */
public final class MallPayPage extends BasePage {

    public MallPayPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openPay(String orderId) {
        open("/pay/" + orderId);
    }

    public void expectPaySuccess() {
        Locator success = page().locator(".pay-strong, .pay-result-title")
                .filter(new Locator.FilterOptions().setHasText("支付成功")).first();
        waitVisible(success, 25_000);
        assertThat(success).containsText("支付成功");
    }
}
