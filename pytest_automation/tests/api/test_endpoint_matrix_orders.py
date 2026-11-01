"""单接口全维度矩阵：POST /api/orders（下单）。

目的：把「大数据量 / 幂等 / 边界值 / 参数组合 / 异常」集中在一个接口下，
报告里一个 feature 就能看到该接口的全部测试点，便于评审与回归定位。

维度与用例命名一一对应（标题前缀即维度）：
- 【边界值】quantity 0 / -1 / 1 / 库存+1、幂等键空白
- 【参数组合】cartItemIds 与 skuId 并存、缺幂等键+地址非法、无明细、数量为 0
- 【幂等】同键同参重放、同键换参重放
- 【大数据量】20 个购物车项合并下单、订单列表 size=100
- 【异常】畸形 JSON、skuId 类型错误、500 个伪造购物车项、未认证
"""
from __future__ import annotations

import time

import pytest

from core.assertions import error, ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload, unique_key

pytestmark = [pytest.mark.api, pytest.mark.validation]

BAD_REQUEST = 40000
STOCK_INSUFFICIENT = 40901


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip("数据库不可达：边界值与大数据量用例需要查库存/明细")
    return True


@pytest.fixture
def on_sale_sku(db_ready):
    row = db.pick_on_sale_sku(min_available=3)
    assert row, "数据库中找不到在售且有库存的 SKU"
    return row


# ================= 维度一：边界值 =================

@pytest.mark.P0
def test_boundary_quantity_lower_than_one(customer, address, on_sale_sku):
    """【边界值】quantity=0 → 400「数量须 ≥ 1」。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 0,
            "idempotentKey": unique_key("order"),
        },
    )
    body = error(resp, BAD_REQUEST)
    assert "数量" in body["msg"]


@pytest.mark.P0
def test_boundary_negative_quantity(customer, address, on_sale_sku):
    """【边界值】quantity=-1 → 400「数量须 ≥ 1」。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": -1,
            "idempotentKey": unique_key("order"),
        },
    )
    body = error(resp, BAD_REQUEST)
    assert "数量" in body["msg"]


@pytest.mark.P1
def test_boundary_quantity_one_accepted(customer, address, on_sale_sku):
    """【边界值】quantity=1（合法下界）→ 创建待支付订单。"""
    data = submit_order(
        customer.client,
        order_payload(
            address_id=address, sku_id=str(on_sale_sku["sku_id"]), quantity=1
        ),
    )
    assert data["status"] == "PENDING_PAY"
    ok(customer.client.post(f"/api/orders/{data['orderId']}/cancel"))


@pytest.mark.P1
def test_boundary_quantity_exceeds_available_stock(customer, address, on_sale_sku):
    """【边界值】quantity = 可售库存 + 1 → 40901 库存不足。"""
    available = int(db.stock_of(str(on_sale_sku["sku_id"]))["available_stock"])
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": available + 1,
            "idempotentKey": unique_key("order"),
        },
    )
    error(resp, STOCK_INSUFFICIENT)


@pytest.mark.P1
def test_boundary_blank_idempotent_key(customer, address, on_sale_sku):
    """【边界值】幂等键为空白串「   」→ 400 必填（空白不等同于已提供）。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 1,
            "idempotentKey": "   ",
        },
    )
    error(resp, BAD_REQUEST)


# ================= 维度二：参数组合 =================

@pytest.mark.P1
def test_combination_cart_items_take_precedence_over_sku(customer, address, on_sale_sku):
    """【参数组合】cartItemIds 与 skuId 同时传 → 以购物车项为准（金额=购物车商品）。"""
    item = add_to_cart(customer.client, str(on_sale_sku["sku_id"]), 1)
    payload = {
        "addressId": address,
        "cartItemIds": [str(item["cartItemId"])],
        "skuId": "10002",           # 干扰项：应立即购买另一个 SKU
        "quantity": 3,
        "idempotentKey": unique_key("order"),
    }
    data = submit_order(customer.client, payload)
    assert float(data["goodsAmount"]) == pytest.approx(float(on_sale_sku["price"]), abs=0.01), (
        "同时传 cartItemIds 与 skuId 时金额未按购物车项计算"
    )
    ok(customer.client.post(f"/api/orders/{data['orderId']}/cancel"))


@pytest.mark.P0
def test_combination_missing_key_reported_before_invalid_address(customer, on_sale_sku):
    """【参数组合】缺幂等键 + 地址非法 + 商品合法 → 先报 idempotentKey。"""
    resp = customer.client.post(
        "/api/orders",
        json={"addressId": "0", "skuId": str(on_sale_sku["sku_id"]), "quantity": 1},
    )
    body = error(resp, BAD_REQUEST)
    assert "idempotentKey" in body["msg"]


@pytest.mark.P1
def test_combination_no_items_and_no_sku(customer, address):
    """【参数组合】地址合规但无购物车项、无 skuId → 400 请选择购物车项或立即购买 SKU。"""
    resp = customer.client.post(
        "/api/orders", json={"addressId": address, "idempotentKey": unique_key("order")}
    )
    body = error(resp, BAD_REQUEST)
    assert "购物车项" in body["msg"] or "SKU" in body["msg"]


@pytest.mark.P1
def test_combination_valid_sku_with_zero_quantity(customer, address, on_sale_sku):
    """【参数组合】地址/SKU 合规、数量为 0 → 400 数量。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 0,
            "idempotentKey": unique_key("order"),
        },
    )
    error(resp, BAD_REQUEST)


# ================= 维度三：幂等 =================

@pytest.mark.P0
def test_idempotency_replay_same_payload(customer, address, on_sale_sku):
    """【幂等】同键同参重复提交 → 同一订单，库存只锁定一次。"""
    sku_id = str(on_sale_sku["sku_id"])
    before = db.stock_of(sku_id)
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address, cart_item_ids=[str(item["cartItemId"])]
    )
    first = submit_order(customer.client, payload)
    second = submit_order(customer.client, payload)

    assert str(first["orderId"]) == str(second["orderId"])
    after = db.stock_of(sku_id)
    assert after["locked_stock"] == before["locked_stock"] + 1, "幂等重放导致二次锁库存"
    ok(customer.client.post(f"/api/orders/{first['orderId']}/cancel"))


@pytest.mark.P0
def test_idempotency_replay_with_changed_payload(customer, address, on_sale_sku):
    """【幂等】同键但换地址/换购物车项 → 仍返回原单，原明细不被篡改。"""
    sku_id = str(on_sale_sku["sku_id"])
    item = add_to_cart(customer.client, sku_id, 1)
    payload = order_payload(
        address_id=address, cart_item_ids=[str(item["cartItemId"])]
    )
    first = submit_order(customer.client, payload)

    other_item = add_to_cart(customer.client, sku_id, 2)
    changed = {**payload, "cartItemIds": [str(other_item["cartItemId"])]}
    second = submit_order(customer.client, changed)

    order_id = str(first["orderId"])
    assert str(second["orderId"]) == order_id
    items = db.order_items(order_id)
    assert len(items) == 1 and int(items[0]["quantity"]) == 1, f"原单明细被篡改：{items}"
    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))


# ================= 维度四：大数据量 =================

@pytest.mark.P1
@pytest.mark.serial
def test_bulk_order_with_twenty_cart_items(customer, address, db_ready):
    """【大数据量】20 个购物车项合并下单：明细 20 行、金额等于逐项求和。"""
    skus = db.query(
        """
        SELECT s.sku_id, k.price
          FROM commerce.stock s
          JOIN commerce.sku k ON k.id = s.sku_id AND k.deleted = FALSE
          JOIN commerce.spu p ON p.id = k.spu_id AND p.deleted = FALSE
         WHERE s.deleted = FALSE AND p.status = 'ON' AND k.status = 'ENABLED'
           AND s.physical_stock - s.locked_stock >= 1
         ORDER BY s.sku_id LIMIT 20
        """
    )
    assert len(skus) == 20, f"可售 SKU 不足 20 个（实际 {len(skus)}）"

    item_ids = []
    for sku in skus:
        item = add_to_cart(customer.client, str(sku["sku_id"]), 1)
        item_ids.append(str(item["cartItemId"]))

    started = time.perf_counter()
    data = submit_order(
        customer.client,
        order_payload(address_id=address, cart_item_ids=item_ids),
    )
    elapsed = (time.perf_counter() - started) * 1000
    order_id = str(data["orderId"])

    detail = ok(customer.client.get(f"/api/orders/{order_id}"))
    assert len(detail["items"]) == 20, f"订单明细 {len(detail['items'])} 行，期望 20"
    expected = sum((sku["price"] for sku in skus), start=0)
    assert float(detail["order"]["totalAmount"]) == pytest.approx(float(expected), abs=0.01)
    assert elapsed < 8000, f"20 行订单提交耗时 {elapsed:.0f}ms，超过 8s"
    ok(customer.client.post(f"/api/orders/{order_id}/cancel"))


@pytest.mark.P1
def test_bulk_order_list_large_page(customer, order):
    """【大数据量】订单列表一次取 100 条：结构稳定、包含当前订单、耗时 < 3s。"""
    started = time.perf_counter()
    data = ok(customer.client.get("/api/orders", params={"page": 1, "size": 100}))
    elapsed = (time.perf_counter() - started) * 1000

    assert elapsed < 3000, f"订单列表 size=100 用时 {elapsed:.0f}ms"
    assert len(data["records"]) <= 100
    assert order["order_id"] in {str(row["id"]) for row in data["records"]}


# ================= 维度五：异常 =================

@pytest.mark.P0
def test_exception_malformed_json(customer, address, on_sale_sku):
    """【异常】请求体截断的畸形 JSON → 应 400。（缺陷 D-16：当前 500 系统异常）"""
    resp = customer.client.request(
        "POST",
        "/api/orders",
        data='{"addressId":',
        headers={"Content-Type": "application/json"},
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P0
def test_exception_non_numeric_sku_id(customer, address):
    """【异常】skuId 传非数字 "abc" → 应 400。（缺陷 D-17：当前 500 系统异常）"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": "abc",
            "quantity": 1,
            "idempotentKey": unique_key("order"),
        },
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_exception_huge_cart_item_ids(customer, address, on_sale_sku):
    """【异常】500 个伪造 cartItemIds → 400（购物车项无效/购物车为空），不得 5xx。"""
    add_to_cart(customer.client, str(on_sale_sku["sku_id"]), 1)  # 保证购物车存在
    fake_ids = [str(9_000_000_000 + i) for i in range(500)]
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "cartItemIds": fake_ids,
            "idempotentKey": unique_key("order"),
        },
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_exception_unauthenticated(anonymous, address, on_sale_sku):
    """【异常】未携带令牌下单 → 401 未认证（不得 400/500）。"""
    resp = anonymous.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 1,
            "idempotentKey": unique_key("order"),
        },
    )
    body = error(resp, http_status=401)
    assert body["code"] in (40100, 40103)
