"""商城个人中心 UI 用例：资料、设置、地址、收藏、足迹、售后、活动位。"""
from __future__ import annotations

import pytest

from pages.mall_account_page import MallAccountPage
from pages.mall_home_page import MallHomePage

pytestmark = [pytest.mark.ui]


class TestMallProfile:
    @pytest.mark.P1
    def test_profile_page_loads(self, logged_in_mall_page):
        page = logged_in_mall_page
        account = MallAccountPage(page)
        account.open("/me")
        text = account.text()
        assert "退出登录" in text
        assert "收货地址" in text
        assert "我的收藏" in text

    @pytest.mark.P2
    def test_settings_page_loads(self, logged_in_mall_page):
        page = logged_in_mall_page
        account = MallAccountPage(page)
        account.open("/settings")
        text = account.text()
        assert "设置" in text
        assert "登录手机号" in text
        assert "Web 与 H5 各自独立" in text

    @pytest.mark.P2
    def test_after_sales_page_loads(self, logged_in_mall_page):
        account = MallAccountPage(logged_in_mall_page)
        account.open_after_sales()
        assert "我的售后" in account.text()


class TestMallAddress:
    @pytest.mark.P1
    def test_address_page_shows_api_address(self, logged_in_mall_page, address):
        account = MallAccountPage(logged_in_mall_page)
        account.open_addresses()
        text = "\n".join(account.address_texts())
        assert "自动化测试" in text
        assert "默认" in text

    @pytest.mark.P1
    def test_add_and_delete_address_via_ui(self, logged_in_mall_page):
        account = MallAccountPage(logged_in_mall_page)
        account.open_addresses()
        assert "暂无地址" in account.text()

        account.add_address(
            ["UI测试", "13900001111", "北京市", "北京市", "朝阳区", "UI自动化地址"]
        )
        text = "\n".join(account.address_texts())
        assert "UI测试" in text and "UI自动化地址" in text

        account.delete_first_address()
        assert "暂无地址" in account.text()


class TestMallFavorites:
    @pytest.mark.P1
    def test_favorites_page_and_remove(self, logged_in_mall_page, customer, sku):
        customer.client.post(f"/api/favorites/{sku['spu_id']}")

        account = MallAccountPage(logged_in_mall_page)
        account.open_favorites()
        assert account.favorite_titles()
        assert "iPhone" in "\n".join(account.favorite_titles())

        account.remove_first_favorite()
        assert "还没有收藏" in account.text()


class TestMallHistory:
    @pytest.mark.P1
    def test_history_page_and_clear(self, logged_in_mall_page, customer, sku):
        customer.client.get(f"/api/products/{sku['spu_id']}")

        account = MallAccountPage(logged_in_mall_page)
        account.open_history()
        assert "iPhone" in "\n".join(account.history_titles())

        account.clear_history()
        assert "还没有足迹" in account.text()


class TestMallPromo:
    @pytest.mark.P2
    def test_promo_page_loads(self, logged_in_mall_page):
        page = logged_in_mall_page
        home = MallHomePage(page)
        home.open_promo("home_promo_subsidy")
        text = home.text()
        assert "活动位" in text
        assert page.locator("a[href^='/product/']").count() > 0
