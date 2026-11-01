"""商城收银台。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallPayPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open_order(self, order_id: str) -> None:
        self.open(f"/pay/{order_id}")

    def wait_success(self) -> None:
        self.page.get_by_text("支付成功", exact=True).first.wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )
