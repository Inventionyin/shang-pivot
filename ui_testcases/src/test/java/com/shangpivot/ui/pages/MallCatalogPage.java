package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * 商城首页与商品详情。锁定 1280×720 → Web 形态（`.web-feed-grid`），不存在 `van-tabbar`。
 * <p>
 * 详情页的加购/购买按钮**只负责开弹层**，真正的动作在规格弹层底部。
 */
public class MallCatalogPage extends BasePage {

    public MallCatalogPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.MALL_URL;
    }

    // ---------------- 首页 ----------------

    public void openHome() {
        open("/");
        assertThat(page().locator(".web-feed-grid").first()).isVisible();
    }

    public Locator firstProductCard() {
        return page().locator(".web-feed-grid .product-card").first();
    }

    /** 首页至少渲染出一张商品卡（演示库有 32 个商品，不该为空）。 */
    public void expectProductsRendered() {
        assertThat(firstProductCard()).isVisible();
    }

    /** 进第一个商品详情，返回卡片上的商品名（后续用于在购物车/订单里定位同一商品）。 */
    public String openFirstProductDetail() {
        String title = firstProductCard().locator(".product-card__title").innerText().trim();
        firstProductCard().click();
        expectPath("/product/");
        assertThat(pageTitle()).isVisible();
        return title;
    }

    // ---------------- 详情页 ----------------

    public Locator pageTitle() {
        return page().locator("h1.page-title");
    }

    public String currentTitle() {
        return pageTitle().innerText().trim();
    }

    /** 页面级「加入购物车」：未选规格会先弹提示，再进规格层。 */
    public void clickPageAddToCart() {
        page().locator(".buy-actions .btn-secondary").click();
    }

    /** 若弹出「请选择规格」提示层，点掉它进入规格层。 */
    public void passSpecNoticeIfPresent() {
        Locator goSpec = page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("去选择规格"));
        if (goSpec.count() > 0 && goSpec.first().isVisible()) {
            goSpec.first().click();
        }
    }

    /** 规格层里每个维度选第一个有货选项；单规格商品没有规格维度，直接跳过。 */
    public void selectFirstAvailableSku() {
        Locator rows = page().locator(".pop-panel .sku-row");
        int dimensions = rows.count();
        if (dimensions == 0) {
            // 单 SKU 商品：弹层里只有「数量」一项，没有规格可选
            assertThat(page().locator(".pop-panel .field-label").first()).isVisible();
            return;
        }
        for (int i = 0; i < dimensions; i++) {
            Locator chips = rows.nth(i).locator("button.chip:not(.is-out)");
            if (chips.count() > 0) {
                chips.first().click();
            }
        }
    }

    /** 规格层底部的「加入购物车」。 */
    public void clickLayerAddToCart() {
        page().locator(".pop-panel .pop-foot").last()
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("加入购物车")).click();
    }

    public void expectAddedToCart() {
        assertThat(page().locator(".pop-title").last()).containsText("已加入购物车");
    }

    /** 加购成功弹层 → 去购物车结算。 */
    public void gotoCartFromSuccessPopup() {
        page().locator(".pop-panel").filter(new Locator.FilterOptions().setHasText("已加入购物车")).last()
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("去购物车结算")).click();
        expectPath("/cart");
    }

    /** 完整加购链路：开详情 → 选规格 → 加购 → 断言成功弹层。 */
    public void addCurrentProductToCart() {
        clickPageAddToCart();
        passSpecNoticeIfPresent();
        selectFirstAvailableSku();
        clickLayerAddToCart();
        expectAddedToCart();
    }
}
