"""收藏与浏览足迹接口用例。"""
from __future__ import annotations

import pytest

from core.assertions import assert_pagination, ok

pytestmark = [pytest.mark.api]


def _ids(records: list, key: str) -> list:
    return [str(row.get(key) or row.get("id")) for row in records]


class TestFavorites:
    @pytest.mark.P1
    def test_add_list_remove_favorite(self, customer, sku):
        spu_id = sku["spu_id"]
        try:
            ok(customer.client.post(f"/api/favorites/{spu_id}"))

            data = ok(customer.client.get("/api/favorites", params={"size": 20}))
            assert_pagination(data)
            assert spu_id in _ids(data["records"], "spuId")

            detail = ok(customer.client.get(f"/api/products/{spu_id}"))
            assert detail["favorited"] is True
        finally:
            ok(customer.client.delete(f"/api/favorites/{spu_id}"))

        detail = ok(customer.client.get(f"/api/products/{spu_id}"))
        assert detail["favorited"] is False

    @pytest.mark.P2
    def test_remove_missing_favorite_is_safe(self, customer, sku):
        """重复取消收藏应幂等，不报 5xx。"""
        resp = customer.client.delete(f"/api/favorites/{sku['spu_id']}")
        ok(resp)


class TestHistory:
    @pytest.mark.P1
    def test_view_product_records_history(self, customer, sku):
        spu_id = sku["spu_id"]
        ok(customer.client.get(f"/api/products/{spu_id}"))

        data = ok(customer.client.get("/api/history", params={"size": 20}))
        assert_pagination(data)
        assert spu_id in _ids(data["records"], "spuId")

    @pytest.mark.P1
    def test_clear_history(self, customer, sku):
        ok(customer.client.get(f"/api/products/{sku['spu_id']}"))
        ok(customer.client.delete("/api/history"))

        data = ok(customer.client.get("/api/history", params={"size": 20}))
        assert data["records"] == []
