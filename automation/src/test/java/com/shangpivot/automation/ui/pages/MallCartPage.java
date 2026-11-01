package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 购物车 `/cart`。 */
public final class MallCartPage extends BasePage {

    public MallCartPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openCart() {
        open("/cart");
        waitVisible(page().locator(".cart-item").first(), 15_000);
    }

    public void expectItemPresent(String title) {
        assertThat(page().locator(".cart-item")
                .filter(new Locator.FilterOptions().setHasText(title)).first()).isVisible();
    }

    /** 取消所有勾选，为“未勾选去结算”负向场景准备前置条件。 */
    public void uncheckAll() {
        Locator items = page().locator(".cart-item");
        for (int i = 0; i < items.count(); i++) {
            Locator item = items.nth(i);
            Locator box = item.locator(".van-checkbox");
            // Vant 4 的选中态在 aria-checked 与 __icon--checked 上，不在根节点 class 上
            boolean checked = "true".equals(box.getAttribute("aria-checked"))
                    || item.locator(".van-checkbox__icon--checked").count() > 0;
            if (checked) {
                box.click();
            }
        }
        assertThat(page().locator(".footer .sum")).containsText("已选 0 件");
    }

    public void clickCheckout() {
        page().locator(".footer .btn-primary").click();
    }

    public void expectSelectionBlocked() {
        expectToastContains("请先勾选商品");
        expectPath("/cart");
    }

    public String selectedSummary() {
        return page().locator(".footer .sum").innerText().trim();
    }
}
