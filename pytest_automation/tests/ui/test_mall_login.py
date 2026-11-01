"""商城登录 UI 用例：正确验证码、错误验证码、手机号格式。"""
from __future__ import annotations

import pytest

from config.settings import settings
from pages.mall_login_page import MallLoginPage

pytestmark = [pytest.mark.ui]


class TestMallLogin:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_login_success(self, mall_page):
        login = MallLoginPage(mall_page)
        login.open()
        login.login(settings.customer_phone, settings.sms_code)
        assert login.is_logged_in(), "登录后顶栏应显示 Hi，<昵称>"

    @pytest.mark.P1
    def test_login_wrong_code_shows_inline_error(self, mall_page):
        login = MallLoginPage(mall_page)
        login.open()
        login.fill(settings.customer_phone, "000000")
        login.submit()
        mall_page.locator(".error-text", has_text="验证码错误").wait_for(
            state="visible", timeout=15000
        )
        assert "验证码错误，请重新获取" in " ".join(login.errors())

    @pytest.mark.P2
    def test_login_invalid_phone_shows_inline_error(self, mall_page):
        login = MallLoginPage(mall_page)
        login.open()
        login.fill("123", settings.sms_code)
        login.submit()
        mall_page.locator(".error-text", has_text="请输入正确的手机号").wait_for(
            state="visible", timeout=10000
        )
