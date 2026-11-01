"""管理端登录页。"""
from __future__ import annotations

from config.settings import settings
from core.base_page import BasePage


class AdminLoginPage(BasePage):
    def __init__(self, page):
        super().__init__(page, settings.ui_admin_url)

    def open(self, path: str = "/login") -> None:
        super().open(path)

    def login(self, username: str, password: str) -> None:
        with self.step("填写管理员账号密码"):
            self.page.fill("input[name=username]", username)
            self.page.fill("input[name=password]", password)
        with self.step("点击登录"):
            self.page.get_by_role("button", name="登 录").click()
            self.wait_path("/dashboard")
            self.page.get_by_text("工作台", exact=True).first.wait_for(
                state="visible", timeout=max(self.timeout, 30000)
            )
