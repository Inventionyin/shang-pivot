"""商城购物车与结算 UI 用例。"""
from __future__ import annotations

import pytest

from core.assertions import ok
from pages.mall_cart_page import MallCartPage
from pages.mall_checkout_page import MallCheckoutPage
from pages.mall_product_page import MallProductPage

pytestmark = [pytest.mark.ui]


def _add_product_to_cart(page) -> str:
    product = MallProductPage(page)
    product.open("101")
    product.add_to_cart()
    product.go_cart()
    return page.url


class TestMallCart:
    @pytest.mark.P0
    def test_add_to_cart_from_product_detail(self, logged_in_mall_page):
        _add_product_to_cart(logged_in_mall_page)
        cart = MallCartPage(logged_in_mall_page)
        cart.wait_items()
        titles = cart.item_titles()
        assert titles, "购物车应有商品"
        assert "iPhone" in " ".join(titles)

    @pytest.mark.P1
    def test_checkout_requires_selected_item(self, logged_in_mall_page):
        _add_product_to_cart(logged_in_mall_page)
        page = logged_in_mall_page

        checkbox = page.locator(".van-checkbox").first
        with page.expect_response(
            lambda resp: "/api/cart/items/" in resp.url and resp.request.method == "PUT"
        ):
            checkbox.click()
        page.wait_for_timeout(500)

        page.get_by_role("button", name="去结算", exact=True).click()
        page.get_by_text("请先勾选商品").wait_for(state="visible", timeout=10000)
        assert "/checkout" not in page.url

    @pytest.mark.P1
    def test_update_cart_quantity(self, logged_in_mall_page):
        _add_product_to_cart(logged_in_mall_page)
        cart = MallCartPage(logged_in_mall_page)
        cart.wait_items()

        before = int(cart.first_quantity())
        cart.increase_first_quantity()
        assert int(cart.first_quantity()) == before + 1

    @pytest.mark.P1
    def test_remove_cart_item(self, logged_in_mall_page):
        _add_product_to_cart(logged_in_mall_page)
        cart = MallCartPage(logged_in_mall_page)
        cart.wait_items()
        assert cart.item_titles()

        cart.remove_first_item()
        assert cart.item_titles() == []


class TestMallCheckout:
    @pytest.mark.P0
    def test_submit_order_from_cart(self, logged_in_mall_page, address, customer):
        _add_product_to_cart(logged_in_mall_page)
        cart = MallCartPage(logged_in_mall_page)
        cart.wait_items()
        cart.go_checkout()

        checkout = MallCheckoutPage(logged_in_mall_page)
        assert checkout.has_address(), "确认订单页应展示收货地址"
        assert checkout.pay_amount_text(), "确认订单页应有应付金额"

        order_id = checkout.submit()
        assert order_id

        detail = ok(customer.client.get(f"/api/orders/{order_id}"))
        status = detail["order"]["status"]
        # 收银台会自动发起沙箱支付，订单可能已经从 PENDING_PAY 变为 PENDING_SHIP
        assert status in ("PENDING_PAY", "PENDING_SHIP")
        assert detail["items"]
