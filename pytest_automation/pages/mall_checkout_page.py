"""确认订单页：提交并跳转收银台。"""
from __future__ import annotations

import re

from config.settings import settings
from core.base_page import BasePage


class MallCheckoutPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/checkout") -> None:
        super().open(path)

    def wait_ready(self) -> None:
        self.page.get_by_text("确认订单", exact=True).wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )

    def has_address(self) -> bool:
        self.wait_ready()
        try:
            self.page.locator(".van-radio-group").wait_for(
                state="visible", timeout=15000
            )
            return True
        except Exception:
            return self.page.locator(".empty-hint", has_text="还没有地址").count() > 0

    def pay_amount_text(self) -> str:
        self.wait_ready()
        text = self.text()
        match = re.search(r"应付\s*¥\s*([\d.]+)", text)
        return match.group(1) if match else ""

    def submit(self) -> str:
        """提交订单，返回生成的 orderId。"""
        self.wait_ready()
        self.page.locator(".van-radio-group").wait_for(
            state="visible", timeout=max(self.timeout, 30000)
        )
        with self.step("点击提交订单"):
            self.page.get_by_role("button", name="提交订单", exact=True).click()
            self.page.get_by_text("确认提交订单", exact=True).wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
        with self.step("确认提交订单"):
            self.page.get_by_role("button", name="确认提交", exact=True).click()
            self.wait_path("/pay/")
        match = re.search(r"/pay/([^/?#]+)", self.url())
        assert match, f"收银台地址不含订单号：{self.url()}"
        return match.group(1)
