"""管理端工作台 / 商品 / 订单 / 会员页。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class AdminConsolePage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_admin_url)

    def open(self, path: str = "/dashboard") -> None:
        super().open(path)

    def wait_dashboard(self) -> None:
        self.page.get_by_text("Phase 1 供给侧工作台", exact=True).wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def goto_products(self) -> None:
        self.open("/products")
        self.page.locator("table tbody tr").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def search_products(self, keyword: str) -> None:
        with self.step(f"搜索商品 {keyword}"):
            self.page.get_by_placeholder("商品名称 / 编码").fill(keyword)
            self.page.get_by_role("button", name="查询", exact=True).click()
            # 等表格本身刷新出关键字（不能等 body：搜索框里的关键字也会命中）
            self.page.wait_for_function(
                "kw => (document.querySelector('table tbody')?.innerText || '').includes(kw)",
                arg=keyword,
                timeout=max(self.timeout, 30000),
            )
            self.page.wait_for_timeout(300)

    def product_rows(self) -> list:
        return self.page.locator("table tbody tr").all_inner_texts()

    def goto_orders(self) -> None:
        self.open("/orders")
        self.page.locator("table tbody tr").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def order_rows(self) -> list:
        return self.page.locator("table tbody tr").all_inner_texts()

    def goto_customers(self) -> None:
        self.open("/customers")
        self.page.locator("table tbody tr").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def customer_rows(self) -> list:
        return self.page.locator("table tbody tr").all_inner_texts()

    def goto_page(self, path: str, title: str) -> str:
        """通用页面加载：等 PageHead 标题出现，返回页面文本。"""
        self.open(path)
        self.page.locator(".page-head .page-title", has_text=title).first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )
        return self.text()

    def goto_product_create(self) -> None:
        self.goto_page("/products/edit", "新增商品")
