"""管理端 UI 用例：登录、工作台、商品列表与搜索、订单列表。"""
from __future__ import annotations

import pytest

from pages.admin_console_page import AdminConsolePage
from pages.admin_login_page import AdminLoginPage

pytestmark = [pytest.mark.ui]

ADMIN_PAGES = [
    ("/categories", "类目与品牌"),
    ("/inventory", "库存管理"),
    ("/in-out", "出入库单"),
    ("/aftersales", "售后管理"),
    ("/reviews", "评价管理"),
    ("/site-brand", "站点品牌"),
    ("/home-slots", "首页运营位"),
    ("/users", "用户管理"),
    ("/roles", "角色管理"),
    ("/menus", "菜单与权限"),
    ("/depts", "部门管理"),
    ("/dicts", "字典管理"),
    ("/logs", "操作日志"),
    ("/settings", "系统设置"),
]


class TestAdminLogin:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_admin_login_lands_on_dashboard(self, admin_page, admin_credentials):
        username, password = admin_credentials
        login = AdminLoginPage(admin_page)
        login.open()
        login.login(username, password)
        assert "/dashboard" in admin_page.url
        assert "工作台" in admin_page.locator("body").inner_text()


class TestAdminConsole:
    @pytest.mark.P0
    def test_dashboard_loads(self, logged_in_admin_page):
        console = AdminConsolePage(logged_in_admin_page)
        console.open("/dashboard")
        console.wait_dashboard()
        assert "在售商品" in console.text()

    @pytest.mark.P1
    def test_product_list_and_search(self, logged_in_admin_page):
        console = AdminConsolePage(logged_in_admin_page)
        console.goto_products()
        assert console.product_rows(), "商品列表应有数据"

        console.search_products("iPhone")
        rows = console.product_rows()
        assert rows, "搜索 iPhone 应有结果"
        assert any("iPhone" in row for row in rows), f"搜索结果不含 iPhone：{rows[:2]}"

    @pytest.mark.P1
    def test_order_list_loads(self, logged_in_admin_page):
        console = AdminConsolePage(logged_in_admin_page)
        console.goto_orders()
        assert console.order_rows(), "订单列表应有数据"
        assert "订单列表" in console.text()

    @pytest.mark.P1
    def test_customers_page_loads(self, logged_in_admin_page):
        console = AdminConsolePage(logged_in_admin_page)
        console.goto_customers()
        assert console.customer_rows(), "会员列表应有数据"
        assert "会员管理" in console.text()

    @pytest.mark.P1
    @pytest.mark.parametrize(
        "path,title",
        ADMIN_PAGES,
        ids=[path.strip("/") for path, _ in ADMIN_PAGES],
    )
    def test_admin_pages_load(self, logged_in_admin_page, path, title):
        console = AdminConsolePage(logged_in_admin_page)
        text = console.goto_page(path, title)
        assert "加载失败" not in text
        assert "系统异常" not in text

    @pytest.mark.P2
    def test_product_create_form_loads(self, logged_in_admin_page):
        console = AdminConsolePage(logged_in_admin_page)
        console.goto_product_create()
        text = console.text()
        assert "新增商品" in text
        assert "存为草稿" in text
