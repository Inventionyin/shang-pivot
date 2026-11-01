"""商城首页与搜索页。"""
from __future__ import annotations

from urllib.parse import quote

from config.settings import settings
from core.base_page import BasePage


class MallHomePage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/") -> None:
        super().open(path)

    def product_links(self):
        return self.page.locator("a[href^='/product/']")

    def wait_products(self) -> None:
        self.product_links().first.wait_for(state="visible", timeout=max(self.timeout, 30000))

    def search(self, keyword: str) -> None:
        with self.step(f"搜索 {keyword}"):
            self.open(f"/search?q={quote(keyword)}")

    def open_promo(self, code: str = "home_promo_subsidy") -> None:
        with self.step(f"打开活动位 {code}"):
            self.open(f"/promo/{code}")
            self.page.get_by_text("← 返回", exact=True).first.wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )

    def search_cards(self):
        return self.page.locator("a[href^='/product/']")

    def is_logged_in(self) -> bool:
        return "亲，请登录" not in self.text()
