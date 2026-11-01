"""收货地址接口用例：增删改查、默认地址、必填校验、越权隔离。"""
from __future__ import annotations

import pytest

from core.assertions import error, ok
from helpers.auth_helper import create_address
from helpers.data_factory import address_payload

pytestmark = [pytest.mark.api]


class TestAddress:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_create_and_list(self, customer):
        payload = address_payload(customer.phone)
        address_id = create_address(customer.client, payload)

        addresses = ok(customer.client.get("/api/addresses"))
        assert isinstance(addresses, list)
        row = next(a for a in addresses if str(a["id"]) == address_id)
        assert row["name"] == payload["name"]
        assert row["detail"] == payload["detail"]
        assert row["isDefault"] is True

        ok(customer.client.delete(f"/api/addresses/{address_id}"))

    @pytest.mark.P1
    def test_update_address(self, customer, address):
        new_detail = "更新后的详细地址"
        ok(
            customer.client.put(
                f"/api/addresses/{address}",
                json={**address_payload(customer.phone), "detail": new_detail},
            )
        )
        addresses = ok(customer.client.get("/api/addresses"))
        row = next(a for a in addresses if str(a["id"]) == address)
        assert row["detail"] == new_detail

    @pytest.mark.P1
    def test_set_default_address(self, customer):
        first = create_address(customer.client, address_payload(customer.phone))
        second_payload = {**address_payload(customer.phone), "isDefault": False}
        second = create_address(customer.client, second_payload)

        ok(customer.client.put(f"/api/addresses/{second}/default"))
        addresses = {str(a["id"]): a for a in ok(customer.client.get("/api/addresses"))}
        assert addresses[second]["isDefault"] is True
        assert addresses[first]["isDefault"] is False

        for address_id in (first, second):
            customer.client.delete(f"/api/addresses/{address_id}")

    @pytest.mark.P2
    def test_incomplete_address_rejected(self, customer):
        payload = address_payload(customer.phone)
        payload.pop("detail")
        resp = customer.client.post("/api/addresses", json=payload)
        body = error(resp, code=40000)
        assert "收货信息" in body["msg"]

    @pytest.mark.P2
    def test_delete_missing_address(self, customer):
        resp = customer.client.delete("/api/addresses/999999999")
        error(resp, code=40400)

    @pytest.mark.security
    @pytest.mark.P2
    def test_cannot_update_other_users_address(self, customer, second_customer):
        address_id = create_address(customer.client, address_payload(customer.phone))
        try:
            resp = second_customer.client.put(
                f"/api/addresses/{address_id}",
                json=address_payload(second_customer.phone),
            )
            error(resp, code=40400)
        finally:
            customer.client.delete(f"/api/addresses/{address_id}")

    @pytest.mark.security
    @pytest.mark.P2
    def test_cannot_delete_other_users_address(self, customer, second_customer):
        address_id = create_address(customer.client, address_payload(customer.phone))
        try:
            resp = second_customer.client.delete(f"/api/addresses/{address_id}")
            error(resp, code=40400)
        finally:
            customer.client.delete(f"/api/addresses/{address_id}")
