"""幂等专项：重复提交/重复操作不得产生重复副作用。

覆盖三条链路的幂等语义（源码依据）：
- 下单：OrderSubmitService 以 (customer_id, idempotent_key) 查重，重放返回原单；
- 支付：PaymentSandboxService 仅允许 PENDING_PAY 支付，重复支付被拒且不重复入账；
- 发货：OrderService.ship 仅允许 PENDING_SHIP，重复发货被拒且不重复写 shipment；
- 加购：CartService.addItem 同 SKU 合并数量，不产生重复行；
- 取消：PENDING_PAY 可取消，CLOSED 重复取消保持幂等成功（见 test_invariants）。
"""
from __future__ import annotations

import pytest

from core.assertions import error, ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload

pytestmark = [pytest.mark.api, pytest.mark.idempotency]

BAD_REQUEST = 40000
NOT_FOUND = 40400
ORDER_STATUS_INVALID = 40902


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip(
            "数据库不可达：幂等用例需要 commerce.payment / shipment / cart_item 做去重断言"
        )
    return True


@pytest.fixture
def db_sku(db_ready):
    row = db.pick_on_sale_sku(min_available=3)
    assert row, "数据库中找不到在售且可售库存 ≥3 的 SKU：演示数据前置条件缺失"
    return row


def _payment_rows(order_id: str) -> int:
    row = db.query_one(
        'SELECT count(*) AS n FROM commerce.payment WHERE order_id = %s AND deleted = FALSE',
        (order_id,),
    )
    return int(row["n"]) if row else 0


def _shipment_rows(order_id: str) -> int:
    row = db.query_one(
        'SELECT count(*) AS n FROM commerce.shipment WHERE order_id = %s AND deleted = FALSE',
        (order_id,),
    )
    return int(row["n"]) if row else 0


@pytest.mark.P0
def test_order_replay_does_not_double_lock_stock(customer, db_sku):
    """同一幂等键重复提交：返回同一订单，库存只锁定一次。"""
    sku_id = str(db_sku["sku_id"])
    before = db.stock_of(sku_id)

    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address_id, cart_item_ids=[str(item["cartItemId"])]
    )

    first = submit_order(customer.client, payload)
    second = submit_order(customer.client, payload)

    assert str(first["orderId"]) == str(second["orderId"]), "幂等键重放返回了不同订单"
    after = db.stock_of(sku_id)
    assert after["locked_stock"] == before["locked_stock"] + 1, (
        "幂等重放导致库存二次锁定："
        f"{before['locked_stock']} -> {after['locked_stock']}"
    )


@pytest.mark.P0
def test_replay_with_different_payload_returns_first_order(customer, db_sku):
    """同一幂等键但请求体不同：仍返回首个订单，不创建第二张单。"""
    sku_id = str(db_sku["sku_id"])
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address_id, cart_item_ids=[str(item["cartItemId"])]
    )
    first = submit_order(customer.client, payload)

    # 换一个地址、换一个购物车项、数量不同，但幂等键不变
    other_address = create_address(customer.client, address_payload(customer.phone))
    other_item = add_to_cart(customer.client, sku_id, 2)
    tampered = {
        **payload,
        "addressId": other_address,
        "cartItemIds": [str(other_item["cartItemId"])],
    }
    second = submit_order(customer.client, tampered)

    order_id = str(first["orderId"])
    assert str(second["orderId"]) == order_id, "幂等键被不同请求体突破，创建了第二张单"

    items = db.order_items(order_id)
    assert len(items) == 1 and int(items[0]["quantity"]) == 1, (
        f"重放请求篡改了原订单明细：{items}"
    )


@pytest.mark.P1
def test_order_without_idempotent_key_rejected(customer, db_sku):
    """缺少 idempotentKey：业务码 40000，不允许无幂等键下单。"""
    sku_id = str(db_sku["sku_id"])
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address_id, cart_item_ids=[str(item["cartItemId"])]
    )
    payload.pop("idempotentKey")

    resp = customer.client.post("/api/orders", json=payload)
    error(resp, BAD_REQUEST)


@pytest.mark.P0
def test_double_payment_rejected_and_single_payment_row(customer, order, db_ready):
    """重复支付：第二次 40902，且 payment 表只有一条记录（不重复入账）。"""
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))

    resp = customer.client.post("/api/payment/sandbox", json={"orderId": order_id})
    error(resp, ORDER_STATUS_INVALID)

    assert _payment_rows(order_id) == 1, "重复支付产生了多条 payment 记录"


@pytest.mark.P1
def test_cart_add_same_sku_merges_into_one_item(customer, db_sku, db_ready):
    """重复加购同一 SKU：合并数量，购物车不产生重复行。"""
    sku_id = str(db_sku["sku_id"])
    add_to_cart(customer.client, sku_id, 1)
    add_to_cart(customer.client, sku_id, 1)

    cart = ok(customer.client.get("/api/cart"))
    matched = [
        row for row in cart.get("items", []) if str(row.get("skuId")) == sku_id
    ]
    assert len(matched) == 1, f"同一 SKU 出现 {len(matched)} 个购物车行"
    assert int(matched[0]["quantity"]) == 2, (
        f"重复加购未合并数量：quantity={matched[0]['quantity']}"
    )
    row = db.query_one(
        """
        SELECT count(*) AS n FROM commerce.cart_item ci
          JOIN commerce.cart c ON c.id = ci.cart_id
         WHERE c.customer_id = (SELECT id FROM identity.customer WHERE phone = %s)
           AND ci.sku_id = %s
        """,
        (customer.phone, sku_id),
    )
    assert int(row["n"]) == 1, "库中存在重复购物车行"


@pytest.mark.P0
def test_admin_ship_twice_blocked_and_single_shipment(customer, admin, order, db_ready):
    """重复发货：第二次 40902，shipment 表只有一条运单记录。"""
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))

    body = {"carrier": "顺丰速运", "trackingNo": f"SF{order_id[-10:]}"}
    ok(admin.post(f"/admin/orders/{order_id}/ship", json=body))

    resp = admin.post(f"/admin/orders/{order_id}/ship", json=body)
    error(resp, ORDER_STATUS_INVALID)

    assert _shipment_rows(order_id) == 1, "重复发货产生了多条 shipment 记录"


@pytest.mark.P2
def test_delete_address_twice_returns_not_found(customer, address):
    """重复删除同一地址：第二次返回 40400（不允许 5xx），不静默成功。"""
    ok(customer.client.delete(f"/api/addresses/{address}"))
    resp = customer.client.delete(f"/api/addresses/{address}")
    error(resp, NOT_FOUND)
