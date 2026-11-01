"""我的订单页与订单详情操作。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallOrdersPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/orders") -> None:
        super().open(path)

    def cards(self):
        return self.page.locator(".order-card")

    def wait_cards(self) -> None:
        self.cards().first.wait_for(state="visible", timeout=max(self.timeout, 30000))

    def card_texts(self) -> list:
        return self.cards().all_inner_texts()

    def wait_detail_loaded(self) -> None:
        self.page.locator(".panel", has_text="订单号").wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def open_detail(self, order_id: str) -> None:
        self.open(f"/orders/{order_id}")
        self.wait_detail_loaded()

    def cancel_pending_order(self) -> None:
        with self.step("点击取消订单"):
            self.page.get_by_role("button", name="取消订单", exact=True).click()
            dialog = self.page.locator(".van-dialog")
            dialog.get_by_text("确认取消该待付款订单？").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("确认取消订单"):
            with self.page.expect_response(
                lambda resp: resp.url.endswith("/cancel") and resp.request.method == "POST"
            ):
                dialog.get_by_role("button", name="确认").click()
            self.page.locator(".panel", has_text="CLOSED").wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
