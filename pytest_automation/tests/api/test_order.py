"""订单接口用例：下单、幂等、列表/详情、取消、支付、边界与越权。"""
from __future__ import annotations

import pytest

from config.settings import settings
from core.api_client import ApiClient
from core.assertions import assert_pagination, error, ok
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload

pytestmark = [pytest.mark.api]


class TestSubmitOrder:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_submit_from_cart(self, customer, order):
        data = order["data"]
        assert data["status"] == "PENDING_PAY"
        assert data["orderNo"].startswith("O")
        assert float(data["payAmount"]) >= float(data["goodsAmount"])
        assert float(data["freight"]) >= 0

        detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        assert str(detail["order"]["id"]) == order["order_id"]
        assert len(detail["items"]) == 1
        assert detail["items"][0]["skuId"] == order["sku"]["sku_id"]

    @pytest.mark.P1
    def test_submit_is_idempotent(self, customer, sku):
        address_id = create_address(customer.client, address_payload(customer.phone))
        item = add_to_cart(customer.client, sku["sku_id"], 1)
        payload = order_payload(
            address_id=address_id, cart_item_ids=[str(item["cartItemId"])]
        )

        first = submit_order(customer.client, payload)
        second = submit_order(customer.client, payload)
        assert str(second["orderId"]) == str(first["orderId"])
        assert second["orderNo"] == first["orderNo"]

    @pytest.mark.P1
    def test_buy_now_without_cart(self, customer, sku, address):
        payload = order_payload(
            address_id=address, sku_id=sku["sku_id"], quantity=1
        )
        data = submit_order(customer.client, payload)
        assert data["status"] == "PENDING_PAY"
        ok(customer.client.post(f"/api/orders/{data['orderId']}/cancel"))

    @pytest.mark.P2
    def test_missing_idempotent_key(self, customer, cart_item, address):
        resp = customer.client.post(
            "/api/orders", json={"addressId": address, "cartItemIds": [cart_item]}
        )
        body = error(resp, code=40000)
        assert "idempotentKey" in body["msg"]

    @pytest.mark.P2
    def test_invalid_address(self, customer, cart_item):
        resp = customer.client.post(
            "/api/orders",
            json={
                "addressId": 999999999,
                "cartItemIds": [cart_item],
                "idempotentKey": "invalid-address-test",
            },
        )
        body = error(resp, code=40000)
        assert "地址" in body["msg"]

    @pytest.mark.security
    @pytest.mark.P2
    def test_cannot_use_other_users_address(self, customer, second_customer, cart_item):
        foreign_address = create_address(
            second_customer.client, address_payload(second_customer.phone)
        )
        resp = customer.client.post(
            "/api/orders",
            json={
                "addressId": foreign_address,
                "cartItemIds": [cart_item],
                "idempotentKey": "foreign-address-test",
            },
        )
        body = error(resp, code=40000)
        assert "地址" in body["msg"]


class TestOrderQuery:
    @pytest.mark.P1
    def test_order_list(self, customer, order):
        data = ok(
            customer.client.get("/api/orders", params={"status": "PENDING_PAY", "size": 10})
        )
        assert_pagination(data)
        ids = [str(row["id"]) for row in data["records"]]
        assert order["order_id"] in ids

    @pytest.mark.P1
    def test_order_detail_has_items_and_snapshot(self, customer, order):
        data = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        assert data["order"]["addressSnapshot"]["name"]
        assert data["items"]
        assert data["shipments"] == []

    @pytest.mark.P2
    def test_order_not_found(self, customer):
        resp = customer.client.get("/api/orders/999999999")
        error(resp, code=40400)

    @pytest.mark.security
    @pytest.mark.P2
    def test_cannot_read_other_users_order(self, order, second_customer):
        resp = second_customer.client.get(f"/api/orders/{order['order_id']}")
        error(resp, code=40400)


class TestOrderState:
    @pytest.mark.P1
    def test_cancel_pending_order(self, customer, order):
        ok(customer.client.post(f"/api/orders/{order['order_id']}/cancel"))
        detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        assert detail["order"]["status"] == "CLOSED"
        assert detail["order"]["closeReason"] == "用户取消"

    @pytest.mark.P1
    def test_cancel_is_idempotent(self, customer, order):
        ok(customer.client.post(f"/api/orders/{order['order_id']}/cancel"))
        ok(customer.client.post(f"/api/orders/{order['order_id']}/cancel"))

    @pytest.mark.P2
    def test_confirm_receive_requires_shipped(self, customer, order):
        resp = customer.client.post(f"/api/orders/{order['order_id']}/confirm")
        error(resp, code=40902)

    @pytest.mark.P0
    def test_sandbox_payment(self, customer, order):
        data = ok(
            customer.client.post(
                "/api/payment/sandbox", json={"orderId": order["order_id"]}
            )
        )
        assert data["status"] == "PENDING_SHIP"
        assert data["tradeNo"] == f"SANDBOX-{order['order_id']}"

        status = ok(customer.client.get(f"/api/payment/{order['order_id']}/status"))
        assert status["orderStatus"] == "PENDING_SHIP"
        assert status["paymentStatus"] == "PAID"

    @pytest.mark.P2
    def test_pay_twice_and_cancel_paid(self, customer, order):
        ok(customer.client.post("/api/payment/sandbox", json={"orderId": order["order_id"]}))
        error(
            customer.client.post(
                "/api/payment/sandbox", json={"orderId": order["order_id"]}
            ),
            code=40902,
        )
        error(
            customer.client.post(f"/api/orders/{order['order_id']}/cancel"),
            code=40902,
        )


@pytest.mark.security
def test_customer_token_cannot_access_admin(customer):
    client = ApiClient(settings.api_admin_base, token=customer.token)
    try:
        resp = client.get("/admin/products")
        body = error(resp, http_status=401)
        assert body["code"] in (40100, 40103)
    finally:
        client.close()
