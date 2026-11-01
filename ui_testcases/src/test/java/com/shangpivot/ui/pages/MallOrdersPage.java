package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 我的订单 `/orders` 与订单详情 `/orders/{id}`。 */
public class MallOrdersPage extends BasePage {

    /** 详情页面板显示的是英文枚举（与列表页中文不一致，是已知的展示差异）。 */
    private static final Pattern STATUS = Pattern.compile("状态\\s*([A-Z_]+)");

    public MallOrdersPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.MALL_URL;
    }

    public void openOrderDetail(String orderId) {
        open("/orders/" + orderId);
        assertThat(page().locator(".panel").first()).isVisible();
    }

    public void openOrderList() {
        open("/orders");
    }

    public String orderStatus() {
        Matcher matcher = STATUS.matcher(page().locator(".panel").first().innerText());
        if (!matcher.find()) {
            throw new AssertionError("订单详情页未渲染状态，实际内容："
                    + page().locator(".panel").first().innerText());
        }
        return matcher.group(1);
    }

    public void clickCancelOrder() {
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("取消订单")).click();
    }

    /** Vant 命令式确认弹窗：标题「取消订单」，默认按钮「确认 / 取消」。 */
    public void confirmVanDialog() {
        Locator dialog = page().locator(".van-dialog").last();
        assertThat(dialog).isVisible();
        Locator confirm = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("确认"));
        if (confirm.count() == 0) {
            confirm = dialog.locator(".van-dialog__confirm");
        }
        confirm.first().click();
    }

    public void cancelOrderAndExpectClosed() {
        clickCancelOrder();
        confirmVanDialog();
        expectToastContains("已取消");
        assertThat(page().locator(".panel").first()).containsText("CLOSED");
    }

    public Locator cardOf(String orderId) {
        return page().locator(".order-card[href$='/" + orderId + "']").first();
    }

    public void expectCardStatus(String orderId, String statusText) {
        assertThat(cardOf(orderId).locator(".status")).hasText(statusText);
    }

    public void expectStatus(String expected) {
        assertThat(page().locator(".panel").first()).containsText(expected);
    }
}
