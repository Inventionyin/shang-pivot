package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 购物车 `/cart` 与结算确认页 `/checkout`。 */
public class MallCartPage extends BasePage {

    private static final Pattern SELECTED_COUNT = Pattern.compile("已选\\s*(\\d+)\\s*件");
    private static final Pattern AMOUNT = Pattern.compile("¥\\s*\\d+(\\.\\d+)?");

    public MallCartPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.MALL_URL;
    }

    // ---------------- 购物车 ----------------

    public void openCart() {
        open("/cart");
        assertThat(page().locator(".cart-item").first()).isVisible();
    }

    public Locator rowOf(String productTitle) {
        return page().locator(".cart-item").filter(new Locator.FilterOptions().setHasText(productTitle)).first();
    }

    public void expectProductInCart(String productTitle) {
        assertThat(rowOf(productTitle)).isVisible();
    }

    public Locator selectedSummary() {
        return page().locator(".footer .sum");
    }

    /** 页脚「已选 N 件」的数量（勾选态由接口准备，见 ScenarioContext#prepareCartSelection）。 */
    public int selectedCount() {
        Matcher matcher = SELECTED_COUNT.matcher(selectedSummary().innerText());
        if (!matcher.find()) {
            throw new AssertionError("页脚没有「已选 N 件」：" + selectedSummary().innerText());
        }
        return Integer.parseInt(matcher.group(1));
    }

    public String totalAmount() {
        return page().locator(".footer .sum .price").innerText().trim();
    }

    public void clickCheckout() {
        page().locator(".footer .btn-primary").click();
    }

    public void expectCheckoutBlockedBySelectionToast() {
        expectToastContains("请先勾选商品");
    }

    // ---------------- 结算确认页 ----------------

    public void expectOnCheckout() {
        expectPath("/checkout");
    }

    /** 结算页商品清单的行数（用来证明只带了勾选的那一件）。 */
    public int goodsLineCount() {
        return page().locator("section.block").filter(new Locator.FilterOptions().setHasText("商品清单"))
                .locator(".line--goods").count();
    }

    public String payableAmount() {
        Locator amount = page().locator(".kv.total .v.num");
        assertThat(amount).isVisible();
        return amount.innerText().trim();
    }

    /** 应付金额必须像金额（防"元素在但内容是空白"这种假通过）。 */
    public void expectPayableLooksLikeMoney() {
        String payable = payableAmount();
        if (!AMOUNT.matcher(payable).matches() && !payable.matches("\\d+(\\.\\d+)?")) {
            throw new AssertionError("应付金额格式异常：" + payable);
        }
    }

    public void expectAddressSectionRendered() {
        Locator section = page().locator("section.block").filter(new Locator.FilterOptions().setHasText("收货地址"));
        assertThat(section).isVisible();
        boolean hasAddress = section.locator(".van-radio-group").count() > 0;
        boolean hasEmptyHint = section.locator(".empty-hint").count() > 0;
        if (!hasAddress && !hasEmptyHint) {
            throw new AssertionError("结算页收货地址区块既没有地址也没有空态提示");
        }
    }

    /** 地址必须被默认选中，否则点提交只会拿到「请先添加收货地址」。 */
    public void expectAddressSelected() {
        Locator checked = page().locator(
                ".van-radio-group .van-radio[aria-checked='true'], .van-radio-group .van-radio__icon--checked");
        assertThat(checked.first()).isVisible();
    }

    public void clickSubmitOrder() {
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交订单")).click();
    }

    public void expectConfirmDialog() {
        assertThat(popup("确认提交订单")).isVisible();
    }

    public String confirmDialogPayable() {
        return popup("确认提交订单").locator(".v.num").last().innerText().trim();
    }
}
