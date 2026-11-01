"""端到端链路：登录 → 地址 → 加购 → 下单 → 支付。"""
from __future__ import annotations

import pytest

from core.assertions import ok
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload
from pages.mall_cart_page import MallCartPage
from pages.mall_checkout_page import MallCheckoutPage
from pages.mall_product_page import MallProductPage

pytestmark = [pytest.mark.e2e]


@pytest.mark.api
@pytest.mark.P0
def test_api_purchase_flow(customer, sku):
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku["sku_id"], 1)
    order = submit_order(
        customer.client,
        order_payload(address_id=address_id, cart_item_ids=[str(item["cartItemId"])]),
    )
    order_id = str(order["orderId"])
    assert order["status"] == "PENDING_PAY"

    paid = ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))
    assert paid["status"] == "PENDING_SHIP"

    detail = ok(customer.client.get(f"/api/orders/{order_id}"))
    assert detail["order"]["status"] == "PENDING_SHIP"
    assert detail["items"][0]["quantity"] == 1


@pytest.mark.ui
@pytest.mark.P1
def test_ui_purchase_flow(logged_in_mall_page, address):
    page = logged_in_mall_page
    product = MallProductPage(page)
    product.open("101")
    product.add_to_cart()
    product.go_cart()

    cart = MallCartPage(page)
    cart.wait_items()
    cart.go_checkout()

    checkout = MallCheckoutPage(page)
    order_id = checkout.submit()
    page.get_by_text("支付成功", exact=True).wait_for(state="visible", timeout=30000)
    assert order_id
