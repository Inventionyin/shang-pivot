"""购物车接口用例：加购、改数量、勾选、删除、失效清理、鉴权与边界。"""
from __future__ import annotations

import pytest

from core.assertions import error, ok
from helpers.auth_helper import add_to_cart

pytestmark = [pytest.mark.api]


def _cart(customer) -> dict:
    return ok(customer.client.get("/api/cart"))


def _items(customer) -> list:
    return _cart(customer).get("items", [])


class TestCart:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_add_and_read_cart(self, customer, sku):
        created = add_to_cart(customer.client, sku["sku_id"], 2)
        assert int(created["quantity"]) == 2

        items = _items(customer)
        assert any(str(item["cartItemId"]) == str(created["cartItemId"]) for item in items)
        item = next(i for i in items if str(i["cartItemId"]) == str(created["cartItemId"]))
        assert str(item["skuId"]) == sku["sku_id"]
        assert int(item["quantity"]) == 2
        assert item["selected"] is True

        cart = _cart(customer)
        assert float(cart["selectedAmount"]) == pytest.approx(sku["price"] * 2)

    @pytest.mark.P1
    def test_update_quantity(self, customer, cart_item):
        ok(customer.client.put(f"/api/cart/items/{cart_item}", json={"quantity": 3}))
        item = next(i for i in _items(customer) if str(i["cartItemId"]) == cart_item)
        assert int(item["quantity"]) == 3

    @pytest.mark.P1
    def test_unselect_item(self, customer, cart_item):
        ok(customer.client.put(f"/api/cart/items/{cart_item}", json={"selected": False}))
        item = next(i for i in _items(customer) if str(i["cartItemId"]) == cart_item)
        assert item["selected"] is False
        assert float(_cart(customer)["selectedAmount"]) == 0

    @pytest.mark.P1
    def test_remove_item(self, customer, cart_item):
        ok(customer.client.delete(f"/api/cart/items/{cart_item}"))
        assert all(str(i["cartItemId"]) != cart_item for i in _items(customer))

    @pytest.mark.P1
    def test_clear_invalid(self, customer, cart_item):
        ok(customer.client.post("/api/cart/clear-invalid"))
        assert all(str(i["cartItemId"]) != cart_item or i.get("valid") for i in _items(customer))

    @pytest.mark.P1
    def test_cart_requires_auth(self, anonymous, sku):
        resp = anonymous.post(
            "/api/cart/items", json={"skuId": sku["sku_id"], "quantity": 1}
        )
        body = error(resp, http_status=401)
        assert body["code"] in (40100, 40103)

    @pytest.mark.P2
    def test_add_unknown_sku(self, customer):
        resp = customer.client.post(
            "/api/cart/items", json={"skuId": "99999999", "quantity": 1}
        )
        error(resp, code=40410)

    @pytest.mark.P2
    @pytest.mark.parametrize("quantity", [0, -1], ids=["zero", "negative"])
    def test_add_invalid_quantity(self, customer, sku, quantity):
        resp = customer.client.post(
            "/api/cart/items", json={"skuId": sku["sku_id"], "quantity": quantity}
        )
        body = error(resp, code=40000)
        assert "数量" in body["msg"]

    @pytest.mark.P2
    def test_add_over_stock(self, customer, sku):
        resp = customer.client.post(
            "/api/cart/items", json={"skuId": sku["sku_id"], "quantity": 100000}
        )
        error(resp, code=40901)

    @pytest.mark.P2
    def test_update_missing_cart_item(self, customer):
        resp = customer.client.put("/api/cart/items/999999999", json={"quantity": 1})
        error(resp, code=40400)
