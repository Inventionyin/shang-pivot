"""商城登录页。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class MallLoginPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_mall_url)

    def open(self, path: str = "/login") -> None:
        super().open(path)

    def fill(self, phone: str, code: str) -> None:
        with self.step("填写手机号和验证码"):
            self.page.get_by_placeholder("请输入 11 位手机号").fill(phone)
            self.page.get_by_placeholder("请输入 6 位验证码").fill(code)

    def submit(self) -> None:
        with self.step("点击登录"):
            self.page.get_by_role("button", name="登录", exact=True).click()

    def login(self, phone: str, code: str) -> None:
        self.fill(phone, code)
        self.submit()
        with self.step("等待登录完成"):
            # 不能写 "**/**"：那会立刻匹配当前 /login，导致还没跳转就断言
            self.page.wait_for_url(f"{self.base_url}/", timeout=max(self.timeout, 30000))
            self.page.get_by_text("Hi，", exact=False).first.wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )

    def errors(self) -> list:
        locator = self.page.locator(".error-text")
        return [locator.nth(i).inner_text().strip() for i in range(locator.count())]

    def is_logged_in(self) -> bool:
        text = self.text()
        return "亲，请登录" not in text and "Hi，" in text
