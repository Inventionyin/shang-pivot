package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 商品详情与规格弹层。 */
public final class MallProductPage extends BasePage {

    public MallProductPage(BrowserSession session) {
        super(session, TestConfig.MALL_BASE_URL);
    }

    public void openProduct(String spuId) {
        open("/product/" + spuId);
        waitVisible(page().locator("h1.page-title"), 15_000);
    }

    public String title() {
        return page().locator("h1.page-title").innerText().trim();
    }

    public void addCurrentProductToCart() {
        page().locator(".buy-actions .btn-secondary").click();
        passSpecNoticeIfPresent();
        selectFirstAvailableSku();
        // 页面上同时存在“请选择规格”提示层与规格层，二者都是 .pop-panel；
        // 必须锁定 .spec-layer，否则 last() 可能命中提示层而点不到加购按钮。
        page().locator(".pop-panel.spec-layer").last()
                .locator(".pop-foot")
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("加入购物车")).click();
        assertThat(page().locator(".pop-title").last()).containsText("已加入购物车");
    }

    private void passSpecNoticeIfPresent() {
        Locator goSpec = page().getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("去选择规格"));
        if (goSpec.count() > 0 && goSpec.first().isVisible()) {
            goSpec.first().click();
        }
    }

    private void selectFirstAvailableSku() {
        Locator rows = page().locator(".pop-panel.spec-layer .sku-row");
        for (int i = 0; i < rows.count(); i++) {
            Locator chips = rows.nth(i).locator("button.chip:not(.is-out)");
            if (chips.count() > 0) {
                chips.first().click();
            }
        }
    }
}
