"""商城订单列表/详情 UI 用例。"""
from __future__ import annotations

import pytest

from core.assertions import ok
from pages.mall_orders_page import MallOrdersPage
from pages.mall_pay_page import MallPayPage

pytestmark = [pytest.mark.ui]


class TestMallOrders:
    @pytest.mark.P1
    def test_order_list_shows_pending_order(self, logged_in_mall_page, order):
        page = logged_in_mall_page
        orders = MallOrdersPage(page)
        orders.open()
        orders.wait_cards()

        texts = " ".join(orders.card_texts())
        assert order["data"]["orderNo"] in texts
        assert "待付款" in texts

    @pytest.mark.P2
    def test_order_detail_page(self, logged_in_mall_page, order):
        page = logged_in_mall_page
        orders = MallOrdersPage(page)
        orders.open()
        orders.wait_cards()
        orders.cards().first.click()
        orders.wait_path(f"/orders/{order['order_id']}")
        orders.wait_detail_loaded()
        assert order["data"]["orderNo"] in page.locator("body").inner_text()

    @pytest.mark.P1
    def test_cancel_pending_order_from_ui(self, logged_in_mall_page, order, customer):
        page = logged_in_mall_page
        orders = MallOrdersPage(page)
        orders.open_detail(order["order_id"])
        orders.cancel_pending_order()

        detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        assert detail["order"]["status"] == "CLOSED"


class TestMallPay:
    @pytest.mark.P1
    def test_pay_page_auto_pays(self, logged_in_mall_page, order, customer):
        pay = MallPayPage(logged_in_mall_page)
        pay.open_order(order["order_id"])
        pay.wait_success()

        detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        assert detail["order"]["status"] == "PENDING_SHIP"
