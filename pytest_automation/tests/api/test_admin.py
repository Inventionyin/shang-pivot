"""管理端接口用例：工作台、商品、订单、会员、类目、品牌、字典、日志。"""
from __future__ import annotations

import pytest

from core.assertions import assert_pagination, error, ok
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload

pytestmark = [pytest.mark.api]


class TestAdminDashboard:
    @pytest.mark.P0
    @pytest.mark.smoke
    def test_dashboard_stats(self, admin):
        data = ok(admin.get("/admin/dashboard/stats"))
        for key in (
            "onShelfProducts",
            "skuTotal",
            "lowStockCount",
            "pendingShipOrders",
            "orderTotal",
        ):
            assert key in data, f"工作台缺少指标 {key}"
            assert str(data[key]).isdigit(), f"{key} 应为数字字符串"


class TestAdminCatalog:
    @pytest.mark.P0
    def test_product_list(self, admin):
        data = ok(admin.get("/admin/products", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)
        assert data["records"]
        row = data["records"][0]
        for key in ("id", "name", "status", "stock"):
            assert key in row

    @pytest.mark.P1
    def test_product_detail(self, admin):
        page = ok(admin.get("/admin/products", params={"page": 1, "size": 1}))
        product_id = page["records"][0]["id"]
        ok(admin.get(f"/admin/products/{product_id}"))

    @pytest.mark.P1
    def test_category_tree(self, admin):
        data = ok(admin.get("/admin/categories/tree"))
        assert isinstance(data, list) and data

    @pytest.mark.P1
    def test_brands_page(self, admin):
        data = ok(admin.get("/admin/brands", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)

    @pytest.mark.P2
    def test_dicts_page(self, admin):
        data = ok(admin.get("/admin/dicts", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)


class TestAdminOrders:
    @pytest.mark.P0
    def test_order_list(self, admin):
        data = ok(admin.get("/admin/orders", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)

    @pytest.mark.P1
    def test_order_detail(self, admin, customer, sku):
        page = ok(admin.get("/admin/orders", params={"page": 1, "size": 1}))
        if page["records"]:
            order_id = page["records"][0]["id"]
        else:
            # 演示库可能刚被清空：先用隔离会员造一笔订单，再查管理端详情
            address_id = create_address(customer.client, address_payload(customer.phone))
            item = add_to_cart(customer.client, sku["sku_id"], 1)
            created = submit_order(
                customer.client,
                order_payload(
                    address_id=address_id,
                    cart_item_ids=[str(item["cartItemId"])],
                ),
            )
            order_id = created["orderId"]
        ok(admin.get(f"/admin/orders/{order_id}"))


class TestAdminMembers:
    @pytest.mark.P1
    def test_customers_page(self, admin):
        data = ok(admin.get("/admin/customers", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)

    @pytest.mark.P2
    def test_operation_logs(self, admin):
        data = ok(admin.get("/admin/operation-logs", params={"page": 1, "size": 5}))
        assert_pagination(data, page=1, size=5)

    @pytest.mark.P2
    def test_missing_product_is_business_error(self, admin):
        resp = admin.get("/admin/products/999999999")
        body = error(resp)
        assert body["code"] in (40400, 40410)
