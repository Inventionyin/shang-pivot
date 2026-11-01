package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 商城首页（Web 形态，1280×720）。 */
public final class MallHomePage extends BasePage {

    public MallHomePage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openHome() {
        open("/");
        waitVisible(page().locator(".web-feed-grid .product-card").first(), 20_000);
    }

    public void expectBrandRendered() {
        assertThat(page().locator("body")).containsText("尚品汇");
        assertThat(page().locator(".web-feed-grid .product-card").first()).isVisible();
    }

    public String openFirstProduct() {
        Locator card = page().locator(".web-feed-grid .product-card").first();
        String title = card.locator(".product-card__title").innerText().trim();
        card.click();
        expectPath("/product/");
        assertThat(page().locator("h1.page-title")).isVisible();
        return title;
    }
}
