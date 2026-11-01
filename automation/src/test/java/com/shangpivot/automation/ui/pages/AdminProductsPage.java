package com.shangpivot.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.core.ui.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 管理端商品列表 `/products`。 */
public final class AdminProductsPage extends BasePage {

    public AdminProductsPage(BrowserSession session) {
        super(session, TestConfig.ADMIN_BASE_URL);
    }

    public void openProducts() {
        open("/products");
        waitVisible(page().locator("table tbody tr").first(), 15_000);
    }

    public int rowCount() {
        return page().locator("table tbody tr").count();
    }

    public String firstRowText() {
        return page().locator("table tbody tr").first().innerText();
    }

    public void search(String keyword) {
        page().getByPlaceholder("商品名称 / 编码").fill(keyword);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
        page().waitForTimeout(800);
    }

    public void expectAllRowsContain(String keyword) {
        Locator rows = page().locator("table tbody tr");
        waitVisible(rows.first(), 10_000);
        int count = rows.count();
        if (count == 0) {
            throw new AssertionError("搜索「" + keyword + "」后没有结果行");
        }
        for (int i = 0; i < count; i++) {
            String text = rows.nth(i).innerText();
            if (!text.contains(keyword)) {
                throw new AssertionError("第 " + (i + 1) + " 行不含关键字「" + keyword + "」: " + text);
            }
        }
        assertThat(page().locator(".pager")).containsText("共");
    }
}
