package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 结算确认页 `/checkout`。 */
public final class MallCheckoutPage extends BasePage {

    public MallCheckoutPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void expectOnCheckout() {
        expectPath("/checkout");
        waitVisible(page().locator("h1.page-title").filter(
                new Locator.FilterOptions().setHasText("确认订单")), 15_000);
    }

    public void expectAddressSelected() {
        Locator checked = page().locator(
                ".van-radio-group .van-radio[aria-checked='true'], .van-radio-group .van-radio__icon--checked");
        assertThat(checked.first()).isVisible();
    }

    public int goodsLineCount() {
        return page().locator("section.block")
                .filter(new Locator.FilterOptions().setHasText("商品清单"))
                .locator(".line--goods").count();
    }

    public String payableAmount() {
        return page().locator(".kv.total .v.num").innerText().trim();
    }

    public void submitOrderAndConfirm() {
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交订单")).click();
        Locator dialog = popup("确认提交订单");
        waitVisible(dialog, 10_000);
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("确认提交")).click();
    }

    public void expectRedirectToPay() {
        expectPath("/pay/");
    }
}
