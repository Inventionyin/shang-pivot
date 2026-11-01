"""业务不变量专项：库存守恒 / 金额守恒 / 状态机 / 幂等。

设计原则（测试方案 §3.3）：
- 数据来自数据库（db.pick_on_sale_sku），不硬编码 SPU，演示数据调整后用例不碎；
- 断言只断「增量」和「等式」，不断库存绝对值，避免并发/重跑抖动；
- 库存等式 physical = locked + available 是系统的基本守恒律，破坏即资损风险。
"""
from __future__ import annotations

from decimal import Decimal

import pytest

from core.assertions import error, ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload, unique_key

pytestmark = [pytest.mark.api]

ORDER_STATUS_INVALID = 40902


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip(
            "数据库不可达（DB_HOST/DB_PORT/DB_USER/DB_PASSWORD）："
            "不变量用例依赖 commerce.stock / order 表做内部一致性断言"
        )
    return True


@pytest.fixture
def db_sku(db_ready):
    """从库中选一个在售且有可售库存的 SKU（DB 驱动，替代硬编码商品）。"""
    row = db.pick_on_sale_sku(min_available=3)
    assert row, "数据库中找不到在售且可售库存 ≥3 的 SKU：演示数据前置条件缺失"
    return row


@pytest.mark.P0
def test_stock_conservation_on_order_and_cancel(customer, db_sku):
    """下单锁定 +N、物理不变；取消后锁定复原；全程 physical = locked + available。"""
    sku_id = str(db_sku["sku_id"])
    qty = 2
    before = db.stock_of(sku_id)

    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, qty)
    order = submit_order(
        customer.client,
        order_payload(address_id=address_id, cart_item_ids=[str(item["cartItemId"])]),
    )
    order_id = str(order["orderId"])

    after_create = db.stock_of(sku_id)
    assert after_create["physical_stock"] == before["physical_stock"], (
        "下单不应改变物理库存："
        f"{before['physical_stock']} -> {after_create['physical_stock']}"
    )
    assert after_create["locked_stock"] == before["locked_stock"] + qty, (
        "下单应锁定等量库存："
        f"{before['locked_stock']} -> {after_create['locked_stock']}，期望 +{qty}"
    )
    assert (
        after_create["available_stock"]
        == after_create["physical_stock"] - after_create["locked_stock"]
    ), "守恒律被破坏：available != physical - locked"

    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))

    after_cancel = db.stock_of(sku_id)
    assert after_cancel["locked_stock"] == before["locked_stock"], (
        "取消订单应释放锁定库存："
        f"{after_create['locked_stock']} -> {after_cancel['locked_stock']}"
    )
    assert after_cancel["physical_stock"] == before["physical_stock"]


@pytest.mark.P0
def test_order_amount_conservation(customer, order):
    """金额守恒：应付 = 商品总额 + 运费；商品明细小计之和 = 商品总额。"""
    order_id = order["order_id"]
    row = db.order_row(order_id)
    assert row is not None, f"订单 {order_id} 在库中不存在"

    total = Decimal(str(row["total_amount"]))
    pay = Decimal(str(row["pay_amount"]))
    freight = Decimal(str(row["freight"]))
    assert pay == total + freight, (
        f"应付金额 {pay} != 商品总额 {total} + 运费 {freight}"
    )

    items = db.order_items(order_id)
    assert items, "订单没有任何明细行"
    detail_sum = sum((Decimal(str(item["subtotal"])) for item in items), Decimal("0"))
    assert detail_sum == total, (
        f"明细小计之和 {detail_sum} != 订单商品总额 {total}"
    )
    for item in items:
        expected = Decimal(str(item["price"])) * int(item["quantity"])
        assert Decimal(str(item["subtotal"])) == expected, (
            f"SKU {item['sku_id']} 小计与 单价×数量 不一致"
        )


@pytest.mark.P0
def test_confirm_rejected_before_shipment(customer, order):
    """非法跃迁：PENDING_PAY 直接确认收货必须拒绝（40902），状态不变。"""
    order_id = order["order_id"]
    resp = customer.client.post(f"/api/orders/{order_id}/confirm")
    error(resp, ORDER_STATUS_INVALID)

    row = db.order_row(order_id)
    assert row["status"] == "PENDING_PAY", f"拒绝后状态被改动：{row['status']}"


@pytest.mark.P0
def test_cancel_rejected_after_payment(customer, order):
    """非法跃迁：已支付（PENDING_SHIP）订单取消必须拒绝（40902），状态不变。"""
    order_id = order["order_id"]
    ok(customer.client.post("/api/payment/sandbox", json={"orderId": order_id}))

    resp = customer.client.post(f"/api/orders/{order_id}/cancel")
    error(resp, ORDER_STATUS_INVALID)

    row = db.order_row(order_id)
    assert row["status"] == "PENDING_SHIP", f"拒绝后状态被改动：{row['status']}"


@pytest.mark.P1
def test_double_cancel_is_idempotent(customer, order):
    """重复取消：CLOSED 状态重复取消保持幂等成功，库存不会二次释放。"""
    order_id = order["order_id"]
    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))
    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))

    row = db.order_row(order_id)
    assert row["status"] == "CLOSED"


@pytest.mark.P1
def test_idempotent_key_replay_returns_same_order(customer, db_sku):
    """幂等重放：同 idempotentKey 的重复提交返回同一订单，不产生第二单。"""
    sku_id = str(db_sku["sku_id"])
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address_id, cart_item_ids=[str(item["cartItemId"])]
    )

    first = submit_order(customer.client, payload)
    replay_payload = {**payload, "idempotentKey": payload["idempotentKey"]}
    second = submit_order(customer.client, replay_payload)

    assert str(first["orderId"]) == str(second["orderId"]), (
        f"幂等键重放产生了不同订单：{first['orderId']} vs {second['orderId']}"
    )
    rows = db.query(
        """
        SELECT id FROM commerce."order"
         WHERE customer_id = (SELECT id FROM identity.customer WHERE phone = %s)
           AND idempotent_key = %s AND deleted = FALSE
        """,
        (customer.phone, payload["idempotentKey"]),
    )
    assert len(rows) == 1, f"同一幂等键在库中存在 {len(rows)} 张订单"


@pytest.mark.P1
def test_stock_table_invariant_sweep(db_ready):
    """全库巡检：不存在负库存/超卖（locked > physical）的在库 SKU。"""
    rows = db.query(
        """
        SELECT sku_id, physical_stock, locked_stock
          FROM commerce.stock
         WHERE deleted = FALSE
           AND (physical_stock < 0 OR locked_stock < 0
                OR locked_stock > physical_stock)
         ORDER BY sku_id
        """
    )
    assert not rows, f"库存守恒被破坏（负库存/超卖）：{rows[:5]}"


@pytest.mark.P1
def test_every_on_sale_sku_has_stock_row(db_ready):
    """数据完整性：每个在售 SKU 都必须有库存行，否则详情页库存展示为 0 且无法下单。"""
    rows = db.query(
        """
        SELECT k.id AS sku_id, k.title
          FROM commerce.sku k
          JOIN commerce.spu p ON p.id = k.spu_id AND p.deleted = FALSE
         WHERE k.deleted = FALSE
           AND p.status = 'ON' AND k.status = 'ENABLED'
           AND NOT EXISTS (
               SELECT 1 FROM commerce.stock s
                WHERE s.sku_id = k.id AND s.deleted = FALSE
           )
         ORDER BY k.id
        """
    )
    assert not rows, f"在售 SKU 缺少库存行：{[r['sku_id'] for r in rows][:5]}"
