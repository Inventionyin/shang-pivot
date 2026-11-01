package com.shangpivot.ui.pages;

import com.microsoft.playwright.Locator;
import com.shangpivot.ui.config.Env;
import com.shangpivot.ui.support.BrowserSession;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** 管理端商品列表 `/products`（列表 + 搜索 + 分页，全部只读）。 */
public class AdminProductsPage extends BasePage {

    public AdminProductsPage(BrowserSession session) {
        super(session);
    }

    @Override
    protected String baseUrl() {
        return Env.ADMIN_URL;
    }

    public void openProducts() {
        open("/products");
        expectLoaded();
    }

    public void expectLoaded() {
        assertThat(page().locator(".page-title")).hasText("商品列表");
        assertThat(page().locator("table.table")).isVisible();
    }

    public Locator toolbarSearch() {
        return page().getByPlaceholder("商品名称 / 编码");
    }

    public int rowCount() {
        Locator rows = page().locator("table.table tbody tr");
        int count = rows.count();
        // 空态是一行 "暂无商品" 的 colspan 单元格，不算数据行
        if (count == 1 && rows.first().locator(".empty-cell").count() > 0) {
            return 0;
        }
        return count;
    }

    public void expectRowsRendered() {
        // 列表是异步拉取的：先等空态行消失（断言自带重试），否则会在数据到达前误判为空
        assertThat(page().locator("table.table tbody tr .empty-cell")).not().isVisible();
        if (rowCount() == 0) {
            throw new AssertionError("商品列表无数据行（演示库应有 32 个商品）");
        }
    }

    /** 分页文案里的总数，形如「共 32 条 · 每页 10 条」。 */
    public int pagerTotal() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("共\\s*(\\d+)\\s*条")
                .matcher(page().locator(".pager").innerText());
        if (!m.find()) {
            throw new AssertionError("分页文案里没有总数：" + page().locator(".pager").innerText());
        }
        return Integer.parseInt(m.group(1));
    }

    public String firstRowName() {
        return page().locator("table.table tbody tr").first().locator("td").nth(2).innerText().trim();
    }

    public void search(String keyword) {
        toolbarSearch().fill(keyword);
        page().locator(".toolbar").getByText("查询").click();
        // 查询是异步的：等待交给后续断言（Playwright 断言自带重试）
    }

    public String pagerText() {
        return page().locator(".pager").innerText().replace("\n", " ").trim();
    }

    public void expectPagerVisible() {
        assertThat(page().locator(".pager")).containsText("共");
    }

    public void expectFirstRowNameContains(String keyword) {
        assertThat(page().locator("table.table tbody tr").first()).containsText(keyword);
    }

    public void expectEmptyState() {
        assertThat(page().locator(".empty-cell")).isVisible();
    }
}
