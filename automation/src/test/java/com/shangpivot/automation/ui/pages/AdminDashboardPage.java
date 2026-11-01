package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 管理端工作台 `/dashboard`：看板数值必须真实渲染，而不是只断言“元素存在”。 */
public final class AdminDashboardPage extends BasePage {

    public AdminDashboardPage(BrowserSession session) {
        super(session, TestConfig.ADMIN_BASE_URL);
    }

    public void expectLoaded() {
        expectPath("/dashboard");
        // 工作台标题在副标题里（Phase 1 供给侧工作台），.page-title 是“上午好，超级管理员”
        waitVisible(page().locator(".stats .stat-value").first(), 15_000);
        assertThat(page().locator("body")).containsText("工作台");
        assertThat(page().locator("body")).containsText("在售商品");
        assertThat(page().locator("body")).containsText("待发货订单");
    }

    public void expectStatValuesNumeric() {
        Locator values = page().locator(".stat-value");
        waitVisible(values.first(), 10_000);
        int count = values.count();
        if (count < 4) {
            throw new AssertionError("工作台看板项少于 4 个，实际 " + count);
        }
        for (int i = 0; i < count; i++) {
            String text = values.nth(i).innerText().trim().replace(",", "");
            if (!text.matches("\\d+")) {
                throw new AssertionError("看板第 " + (i + 1) + " 项不是数字: " + text);
            }
        }
    }
}
