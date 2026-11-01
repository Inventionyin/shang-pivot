"""购物车页。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallCartPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/cart") -> None:
        super().open(path)

    def item_titles(self) -> list:
        return [text.strip() for text in self.page.locator(".cart-item .title").all_inner_texts()]

    def wait_items(self) -> None:
        self.page.locator(".cart-item").first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def first_quantity(self) -> str:
        return self.page.locator(".van-stepper__input").first.input_value()

    def increase_first_quantity(self) -> None:
        with self.step("增加购物车商品数量"):
            with self.page.expect_response(
                lambda resp: "/api/cart/items/" in resp.url and resp.request.method == "PUT"
            ):
                self.page.locator(".van-stepper__plus").first.click()
            self.page.wait_for_timeout(400)

    def remove_first_item(self) -> None:
        with self.step("点击删除商品"):
            # 右侧悬浮快捷入口会拦截鼠标事件：直接派发 DOM click，命中购物车行内按钮
            self.page.locator(".cart-item button.link", has_text="删除").first.dispatch_event("click")
            self.page.locator(".pop-title", has_text="删除商品").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("确认删除商品"):
            with self.page.expect_response(
                lambda resp: "/api/cart/items/" in resp.url and resp.request.method == "DELETE"
            ):
                self.page.locator(".pop-panel .btn-danger").click()
            self.page.wait_for_timeout(500)

    def go_checkout(self) -> None:
        with self.step("前往结算"):
            self.page.get_by_role("button", name="去结算", exact=True).click()
            self.wait_path("/checkout")
