"""商品详情页：规格选择、加入购物车、收藏。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallProductPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, spu_id: str = "101") -> None:
        super().open(f"/product/{spu_id}")

    def add_to_cart(self) -> None:
        """完整加购流程：点加购 →（未选规格提示）→ 选规格 → 确认加购。"""
        with self.step("点击加入购物车"):
            self.page.get_by_role("button", name="加入购物车", exact=True).first.click()

        notice = self.page.get_by_text("请选择规格", exact=True)
        try:
            if notice.is_visible(timeout=3000):
                with self.step("前往选择规格"):
                    self.page.get_by_role("button", name="去选择规格").click()
        except Exception:
            # 已预选规格时不会出现提示，直接进入规格层
            pass

        layer = self.page.locator(".pop-panel.spec-layer")
        layer.wait_for(state="visible", timeout=max(self.timeout, 30000))

        with self.step("选择商品规格"):
            rows = layer.locator(".sku-row")
            for index in range(rows.count()):
                available = rows.nth(index).locator(".chip:not(.is-out)")
                if available.count():
                    available.first.click()

        with self.step("确认加入购物车"):
            layer.get_by_role("button", name="加入购物车", exact=True).click()
            self.page.locator(".pop-title", has_text="已加入购物车").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )

    def go_cart(self) -> None:
        with self.step("前往购物车"):
            self.page.get_by_role("button", name="去购物车结算").click()
            self.wait_path("/cart")

    def favorite_text(self) -> str:
        return self.page.locator(".fav-link").inner_text().strip()

    def toggle_favorite(self, expected_text: str = None) -> None:
        with self.step("切换收藏状态"):
            with self.page.expect_response(lambda resp: "/api/favorites/" in resp.url):
                self.page.locator(".fav-link").click()
            if expected_text:
                self.page.get_by_role("button", name=expected_text, exact=True).wait_for(
                    state="visible", timeout=max(self.timeout, 30000)
                )

    def is_favorited(self) -> bool:
        return "已收藏" in self.favorite_text()
