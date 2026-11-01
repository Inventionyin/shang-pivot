"""售后与评价接口用例：状态机校验 + 公开评价列表。"""
from __future__ import annotations

import pytest

from core.assertions import assert_pagination, error, ok

pytestmark = [pytest.mark.api]


class TestAfterSales:
    @pytest.mark.P2
    def test_apply_rejected_for_pending_pay(self, customer, order):
        """售后仅允许待收货/已完成；待支付订单必须拒绝。"""
        resp = customer.client.post(
            "/api/after-sales",
            json={
                "orderId": order["order_id"],
                "type": "REFUND",
                "reason": "不想要了",
            },
        )
        error(resp, code=40902)

    @pytest.mark.P2
    def test_apply_rejects_invalid_type(self, customer, order):
        resp = customer.client.post(
            "/api/after-sales",
            json={
                "orderId": order["order_id"],
                "type": "NOT_A_TYPE",
                "reason": "测试非法类型",
            },
        )
        body = error(resp, code=40000)
        assert "type" in body["msg"]

    @pytest.mark.P2
    def test_apply_requires_fields(self, customer):
        resp = customer.client.post("/api/after-sales", json={"orderId": None})
        body = error(resp, code=40000)
        assert "必填" in body["msg"]

    @pytest.mark.P1
    def test_my_after_sales_page(self, customer):
        data = ok(customer.client.get("/api/after-sales", params={"size": 10}))
        assert_pagination(data)


class TestReviews:
    @pytest.mark.P2
    def test_review_rejected_before_completed(self, customer, order):
        detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
        item_id = detail["items"][0]["id"]
        resp = customer.client.post(
            "/api/reviews",
            json={"orderItemId": item_id, "rating": 5, "content": "自动化评价"},
        )
        body = error(resp, code=40902)
        assert "完成" in body["msg"]

    @pytest.mark.P1
    def test_product_reviews_public(self, anonymous, sku):
        data = ok(
            anonymous.get(f"/api/products/{sku['spu_id']}/reviews", params={"size": 10})
        )
        assert_pagination(data)
