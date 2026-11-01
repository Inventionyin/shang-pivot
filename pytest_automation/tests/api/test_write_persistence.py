"""写接口落库校验：请求入参 ↔ DB 行逐字段比对（契约见 data/write_contracts.yaml）。

定位策略：写接口返回的 ID / 路径 ID / 自然唯一键（order_id）；
生成列只断言非空；Long 主键按字符串、Decimal 按数值、JSONB 按 JSON 语义比较。
"""
from __future__ import annotations

from decimal import Decimal

import pytest

from core.assertions import ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload
from helpers.db_contract import verify_deleted, verify_write

pytestmark = [pytest.mark.api, pytest.mark.db, pytest.mark.regression]


@pytest.fixture(scope="module")
def category_id():
    if not db.available():
        pytest.skip("数据库不可达：需要类目 ID 构造品牌")
    row = db.query_one(
        "SELECT id FROM commerce.category WHERE deleted = FALSE ORDER BY id LIMIT 1"
    )
    assert row, "演示数据缺少类目"
    return str(row["id"])


def _shipped_order(customer, admin, sku_id: str, confirm: bool = False) -> str:
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, 1)
    order = submit_order(
        customer.client,
        order_payload(address_id=address_id, cart_item_ids=[str(item["cartItemId"])]),
    )
    order_id = str(order["orderId"])
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))
    ok(
        admin.post(
            f"/admin/orders/{order_id}/ship",
            json={"carrier": "顺丰速运", "trackingNo": f"SF{order_id[-8:]}"},
        )
    )
    if confirm:
        ok(customer.client.post(f"/api/orders/{order_id}/confirm"))
    return order_id


def _order_item_id(order_id: str) -> str:
    row = db.query_one(
        "SELECT id FROM commerce.order_item WHERE order_id = %s AND deleted = FALSE LIMIT 1",
        (order_id,),
    )
    assert row, f"订单 {order_id} 无明细"
    return str(row["id"])


@pytest.mark.P0
def test_address_create_and_update_persisted(customer):
    payload = address_payload(customer.phone)
    address_id = create_address(customer.client, payload)
    verify_write("POST /api/addresses", payload, response=address_id)

    payload["detail"] = "自动化-落库更新"
    ok(customer.client.put(f"/api/addresses/{address_id}", json=payload))
    verify_write("PUT /api/addresses/{id}", payload, path={"id": address_id})

    ok(customer.client.put(f"/api/addresses/{address_id}/default", json={"isDefault": True}))
    verify_write("PUT /api/addresses/{id}/default", {"isDefault": True}, path={"id": address_id})
    ok(customer.client.delete(f"/api/addresses/{address_id}"))
    verify_deleted("DELETE /api/addresses/{id}", path={"id": address_id})


@pytest.mark.P1
def test_cart_item_persisted(customer, sku):
    item = add_to_cart(customer.client, sku["sku_id"], 2)
    verify_write(
        "POST /api/cart/items",
        {"skuId": sku["sku_id"], "quantity": 2},
        response=item,
    )
    cart_item_id = str(item["cartItemId"])
    ok(
        customer.client.put(
            f"/api/cart/items/{cart_item_id}", json={"quantity": 3, "selected": True}
        )
    )
    verify_write(
        "PUT /api/cart/items/{id}",
        {"quantity": 3, "selected": True},
        path={"id": cart_item_id},
    )
    ok(customer.client.delete(f"/api/cart/items/{cart_item_id}"))
    verify_deleted("DELETE /api/cart/items/{id}", path={"id": cart_item_id})


@pytest.mark.P0
def test_order_submit_persisted(customer, address, on_sale_sku_fixture):
    sku = on_sale_sku_fixture
    before = db.stock_of(str(sku["sku_id"]))
    item = add_to_cart(customer.client, str(sku["sku_id"]), 2)
    payload = order_payload(
        address_id=address, cart_item_ids=[str(item["cartItemId"])]
    )
    data = submit_order(customer.client, payload)
    order_id = str(data["orderId"])

    row = db.order_row(order_id)
    assert row["idempotent_key"] == payload["idempotentKey"]
    assert row["status"] == "PENDING_PAY"
    assert Decimal(str(row["pay_amount"])) == Decimal(str(row["total_amount"])) + Decimal(str(row["freight"]))

    items = db.order_items(order_id)
    assert len(items) == 1 and int(items[0]["quantity"]) == 2
    snapshot = db.query_one(
        'SELECT address_snapshot FROM commerce."order" WHERE id = %s', (order_id,)
    )["address_snapshot"]
    assert snapshot.get("name") and snapshot.get("phone")

    after = db.stock_of(str(sku["sku_id"]))
    assert after["locked_stock"] == before["locked_stock"] + 2, "订单未正确锁定库存"
    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))
    row = verify_write("POST /api/orders/{id}/cancel", {}, path={"id": order_id})
    assert row["status"] == "CLOSED"


@pytest.fixture
def on_sale_sku_fixture():
    row = db.pick_on_sale_sku(min_available=3)
    assert row, "缺少可售库存 SKU"
    return row


@pytest.mark.P0
def test_payment_persisted(customer, order):
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))
    row = verify_write(
        "POST /api/payment/sandbox",
        {"orderId": order_id},
        natural={"order_id": order_id},
    )
    assert row["status"] == "PAID" and row["channel"] == "SANDBOX"
    assert Decimal(str(row["amount"])) == Decimal(str(db.order_row(order_id)["pay_amount"]))
    assert db.order_row(order_id)["status"] == "PENDING_SHIP"


@pytest.mark.P0
def test_shipment_persisted(customer, admin, order):
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))
    body = {"carrier": "顺丰速运", "trackingNo": f"SF{order_id[-8:]}"}
    ok(admin.post(f"/admin/orders/{order_id}/ship", json=body))
    verify_write(
        "POST /admin/orders/{id}/ship", body, natural={"order_id": order_id}
    )
    assert db.order_row(order_id)["status"] == "PENDING_RECEIVE"


@pytest.mark.P1
def test_review_persisted(customer, admin, order, on_sale_sku_fixture):
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))
    ok(
        admin.post(
            f"/admin/orders/{order_id}/ship",
            json={"carrier": "顺丰速运", "trackingNo": f"SF{order_id[-8:]}"},
        )
    )
    ok(customer.client.post(f"/api/orders/{order_id}/confirm"))
    payload = {
        "orderItemId": _order_item_id(order_id),
        "rating": 5,
        "content": "落库校验评价",
        "picKeys": ["PRODUCT/seed/product_1.jpg"],
    }
    review_id = ok(customer.client.post("/api/reviews", json=payload))
    verify_write("POST /api/reviews", payload, response=review_id)

    ok(admin.put(f"/admin/reviews/{review_id}/status", json={"status": "HIDDEN"}))
    verify_write(
        "PUT /admin/reviews/{id}/status", {"status": "HIDDEN"}, path={"id": review_id}
    )


@pytest.mark.P1
def test_after_sale_persisted(customer, admin, on_sale_sku_fixture):
    order_id = _shipped_order(customer, admin, str(on_sale_sku_fixture["sku_id"]))
    payload = {
        "orderId": order_id,
        "type": "REFUND",
        "reason": "落库校验售后",
        "evidenceKeys": ["PRODUCT/seed/product_1.jpg"],
    }
    after_sale_id = ok(customer.client.post("/api/after-sales", json=payload))
    verify_write("POST /api/after-sales", payload, response=after_sale_id)

    audit = {"approved": True, "remark": "落库校验审核通过"}
    ok(admin.post(f"/admin/after-sales/{after_sale_id}/audit", json=audit))
    row = verify_write(
        "POST /admin/after-sales/{id}/audit", audit, path={"id": after_sale_id}
    )
    assert row["status"] == "COMPLETED"


@pytest.mark.P1
def test_admin_brand_persisted(admin, category_id):
    payload = {"name": "落库校验品牌", "categoryId": category_id}
    brand_id = ok(admin.post("/admin/brands", json=payload))
    verify_write("POST /admin/brands", payload, response=brand_id)
    ok(admin.delete(f"/admin/brands/{brand_id}"))


@pytest.mark.P1
def test_admin_category_persisted(admin):
    payload = {"name": "落库校验类目", "parentId": "0"}
    category = ok(admin.post("/admin/categories", json=payload))
    verify_write("POST /admin/categories", payload, response=category)
    ok(admin.delete(f"/admin/categories/{category}"))


@pytest.mark.P1
def test_admin_dict_persisted(admin):
    payload = {"code": "persist_check_dict", "name": "落库校验字典"}
    dict_id = ok(admin.post("/admin/dicts", json=payload))
    verify_write("POST /admin/dicts", payload, response=dict_id)
    ok(admin.delete(f"/admin/dicts/{dict_id}"))


@pytest.mark.P1
def test_admin_dept_persisted(admin):
    payload = {"name": "落库校验部门", "parentId": "0"}
    dept_id = ok(admin.post("/admin/depts", json=payload))
    verify_write("POST /admin/depts", payload, response=dept_id)
    ok(admin.delete(f"/admin/depts/{dept_id}"))


@pytest.mark.P1
def test_admin_warehouse_persisted(admin):
    payload = {"name": "落库校验仓库"}
    warehouse_id = ok(admin.post("/admin/warehouses", json=payload))
    verify_write("POST /admin/warehouses", payload, response=warehouse_id)


@pytest.mark.P1
def test_customer_status_persisted(admin, customer):
    payload = {"status": "FROZEN"}
    ok(admin.put(f"/admin/customers/{customer.info['id']}/status", json=payload))
    verify_write(
        "PUT /admin/customers/{id}/status", payload, path={"id": customer.info["id"]}
    )
    ok(admin.put(f"/admin/customers/{customer.info['id']}/status", json={"status": "NORMAL"}))


@pytest.mark.P1
def test_admin_permission_crud_persisted(admin):
    code = f"perm_{__import__('uuid').uuid4().hex[:8]}"
    payload = {"code": code, "name": "落库校验权限", "type": "MENU", "path": "/tmp"}
    permission_id = ok(admin.post("/admin/permissions", json=payload))
    verify_write("POST /admin/permissions", payload, response=permission_id)

    update = {"name": "落库校验权限-更新", "type": "MENU", "path": "/tmp2", "status": "DISABLED"}
    ok(admin.put(f"/admin/permissions/{permission_id}", json=update))
    verify_write("PUT /admin/permissions/{id}", update, path={"id": permission_id})

    ok(admin.delete(f"/admin/permissions/{permission_id}"))
    verify_deleted("DELETE /admin/permissions/{id}", path={"id": permission_id})


@pytest.mark.P1
def test_order_confirm_persisted(customer, admin, on_sale_sku_fixture):
    order_id = _shipped_order(
        customer, admin, str(on_sale_sku_fixture["sku_id"]), confirm=True
    )
    row = verify_write("POST /api/orders/{id}/confirm", {}, path={"id": order_id})
    assert row["status"] == "COMPLETED" and row["received_at"] is not None
