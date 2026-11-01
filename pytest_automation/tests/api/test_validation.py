"""输入校验专项：等价类划分 + 边界值分析。

设计依据（源码真值，不臆造规则）：
- 认证 DTO 有 Bean Validation：@NotBlank、手机号 ^1\\d{10}$、clientType ^(web|h5)$；
- 地址：AddressService.apply 只校验「必填/非空」，**无长度与格式校验**，
  但 DB 列有约束：name/province/city/district VARCHAR(64)、detail VARCHAR(255)、phone VARCHAR(20)；
- 购物车：quantity ≥ 1（服务端校验），超库存 40901；
- 下单：addressId 必须存在、cartItemIds 或 skuId 二选一、quantity ≥ 1；
- 售后：orderId/type/reason 必填、type 为枚举、reason 列 VARCHAR(512)；
- 评价：rating 直接 Integer.parseInt 落库（smallint），**无范围与类型校验**。

断言策略：非法输入应返回 4xx（400/409），5xx 视为缺陷（D-xx 标注在用例 docstring）。
"""
from __future__ import annotations

import pytest

from core.assertions import error, ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload

pytestmark = [pytest.mark.api, pytest.mark.validation]

BAD_REQUEST = 40000
STOCK_INSUFFICIENT = 40901


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip("数据库不可达：构造已完成订单与售后单需要查 order_item")
    return True


# ---------- 构造辅助：不同状态的订单（评价/售后校验需要） ----------

def _paid_shipped_order(customer, admin, sku_id: str, confirm: bool) -> str:
    """下单 → 支付 → 发货（可选确认收货），返回 orderId。"""
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
        'SELECT id FROM commerce.order_item WHERE order_id = %s AND deleted = FALSE LIMIT 1',
        (order_id,),
    )
    assert row, f"订单 {order_id} 缺少明细行"
    return str(row["id"])


@pytest.fixture
def on_sale_sku(db_ready):
    row = db.pick_on_sale_sku(min_available=2)
    assert row, "数据库中找不到在售且有库存的 SKU"
    return row


# ---------- 认证：手机号/验证码/密码 等价类与边界 ----------

@pytest.mark.P1
def test_login_phone_10_digits_rejected(anonymous):
    """等价类：手机号长度不足（10 位）→ 400/40000。"""
    resp = anonymous.post(
        "/api/auth/login",
        json={"phone": "1398888000", "code": "123456", "clientType": "web"},
    )
    error(resp, BAD_REQUEST, http_status=400)


@pytest.mark.P1
def test_login_phone_12_digits_rejected(anonymous):
    """边界值：手机号 11+1 位 → 400/40000。"""
    resp = anonymous.post(
        "/api/auth/login",
        json={"phone": "139888800011", "code": "123456", "clientType": "web"},
    )
    error(resp, BAD_REQUEST, http_status=400)


@pytest.mark.P2
def test_login_code_length_boundary_handled(anonymous):
    """边界值：验证码 5 位/7 位（非 6 位）应被判定为凭证错误，而不是 5xx。"""
    for code in ("12345", "1234567"):
        resp = anonymous.post(
            "/api/auth/login",
            json={"phone": "13988880001", "code": code, "clientType": "web"},
        )
        body = error(resp)  # 业务失败，具体码 40101 表示验证码错误
        assert body["code"] == 40101, f"验证码 {code} 返回 {body['code']}"


@pytest.mark.P2
@pytest.mark.serial
def test_admin_password_200_chars_not_5xx(anonymous):
    """边界值：管理端密码超长（200 字符）→ 40101 凭证错误，不得 5xx。"""
    resp = anonymous.post(
        "/admin/auth/login",
        json={"username": "admin", "password": "A" * 200},
    )
    body = error(resp)
    assert body["code"] == 40101, f"超长密码返回 {body['code']}"


# ---------- 收货地址：长度边界 / 空白 / 格式 ----------

@pytest.mark.P1
def test_address_name_64_chars_accepted(customer):
    """边界值：name 恰为列宽上限 64 → 允许保存。"""
    payload = address_payload(customer.phone) | {"name": "测" * 64}
    address_id = create_address(customer.client, payload)
    addresses = ok(customer.client.get("/api/addresses"))
    saved = next(row for row in addresses if str(row["id"]) == address_id)
    assert len(saved["name"]) == 64


@pytest.mark.P0
def test_address_name_65_chars_rejected(customer):
    """边界值：name 64+1 位 → 应 400/40000。（缺陷 D-06：当前 500 系统异常）"""
    payload = address_payload(customer.phone) | {"name": "测" * 65}
    resp = customer.client.post("/api/addresses", json=payload)
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_address_detail_255_chars_accepted(customer):
    """边界值：detail 恰为列宽上限 255 → 允许保存。"""
    payload = address_payload(customer.phone) | {"detail": "x" * 255}
    create_address(customer.client, payload)


@pytest.mark.P0
def test_address_detail_256_chars_rejected(customer):
    """边界值：detail 255+1 位 → 应 400/40000。（缺陷 D-07：当前 500 系统异常）"""
    payload = address_payload(customer.phone) | {"detail": "x" * 256}
    resp = customer.client.post("/api/addresses", json=payload)
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_address_phone_invalid_format_rejected(customer):
    """等价类：收货人手机号格式非法（非数字）→ 应 400。（缺陷 D-08：当前被接受）"""
    payload = address_payload(customer.phone) | {"phone": "abc"}
    resp = customer.client.post("/api/addresses", json=payload)
    error(resp, BAD_REQUEST)


@pytest.mark.P2
def test_address_whitespace_only_name_rejected(customer):
    """等价类：姓名全空白（非空串但无有效字符）→ 400「收货信息不完整」。"""
    payload = address_payload(customer.phone) | {"name": "   "}
    resp = customer.client.post("/api/addresses", json=payload)
    body = error(resp, BAD_REQUEST)
    assert "收货信息不完整" in body["msg"]


# ---------- 分页参数：零值/负值/超大值 ----------

@pytest.mark.P2
def test_pagination_zero_and_negative_tolerated(anonymous):
    """边界值：page=0 / size=0 / page=-1 不得 5xx（当前策略为容忍并返回空/首页）。"""
    for params in (
        {"page": 0, "size": 0},
        {"page": -1, "size": 5},
        {"page": 0, "size": 20},
    ):
        resp = anonymous.get("/api/products", params=params)
        data = ok(resp)
        assert isinstance(data["records"], list), f"{params} 返回结构异常"


@pytest.mark.P2
def test_pagination_oversize_tolerated(anonymous):
    """边界值：size=1000（远超默认 20）→ 200 且不超过总记录数，不得 5xx。"""
    resp = anonymous.get("/api/products", params={"page": 1, "size": 1000})
    data = ok(resp)
    assert len(data["records"]) <= int(data["total"])


# ---------- 下单：空明细 / 立即购买数量边界 ----------

@pytest.mark.P1
def test_order_empty_cart_items_rejected(customer, address):
    """等价类：购物车项为空且未指定 skuId → 400（不得创建空单）。"""
    resp = customer.client.post(
        "/api/orders",
        json={"addressId": address, "cartItemIds": [], "idempotentKey": "empty-items"},
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_order_buy_now_quantity_zero_rejected(customer, address, on_sale_sku):
    """边界值：立即购买 quantity=0 → 400「数量须 ≥ 1」。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": on_sale_sku["sku_id"],
            "quantity": 0,
            "idempotentKey": "buynow-qty0",
        },
    )
    body = error(resp, BAD_REQUEST)
    assert "数量" in body["msg"]


@pytest.mark.P1
def test_order_buy_now_over_stock_rejected(customer, address, on_sale_sku):
    """边界值：立即购买数量 = 可售库存 + 999999 → 40901 库存不足。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": on_sale_sku["sku_id"],
            "quantity": 999999,
            "idempotentKey": "buynow-overstock",
        },
    )
    error(resp, STOCK_INSUFFICIENT)


# ---------- 售后：必填 / 原因长度边界 ----------

@pytest.mark.P1
def test_after_sale_missing_fields_rejected(customer):
    """必填性：orderId/type/reason 缺一 → 400「订单、类型、原因必填」。"""
    for payload in (
        {"type": "REFUND", "reason": "x"},
        {"orderId": "1", "reason": "x"},
        {"orderId": "1", "type": "REFUND"},
    ):
        resp = customer.client.post("/api/after-sales", json=payload)
        body = error(resp, BAD_REQUEST)
        assert "必填" in body["msg"]


@pytest.mark.P1
def test_after_sale_type_enum_rejected(customer):
    """等价类：type 不在枚举（REFUND/RETURN_REFUND/EXCHANGE）→ 400「type 无效」。"""
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": "1", "type": "BAD_TYPE", "reason": "x"},
    )
    body = error(resp, BAD_REQUEST)
    assert "type 无效" in body["msg"]


@pytest.mark.P1
def test_after_sale_reason_512_accepted(customer, admin, on_sale_sku):
    """边界值：reason 恰为列宽上限 512 → 允许提交售后。"""
    order_id = _paid_shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=False)
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": order_id, "type": "REFUND", "reason": "r" * 512},
    )
    ok(resp)


@pytest.mark.P0
def test_after_sale_reason_513_rejected(customer, admin, on_sale_sku):
    """边界值：reason 512+1 位 → 应 400。（缺陷 D-10：当前 500 系统异常）"""
    order_id = _paid_shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=False)
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": order_id, "type": "REFUND", "reason": "r" * 513},
    )
    error(resp, BAD_REQUEST)


# ---------- 评价：必填 / 评分范围与类型 ----------

@pytest.mark.P1
def test_review_missing_fields_rejected(customer):
    """必填性：评价缺少 orderItemId/rating → 应 400。（缺陷 D-09：当前 500/空指针）"""
    resp = customer.client.post("/api/reviews", json={})
    error(resp, BAD_REQUEST)


@pytest.mark.P0
def test_review_rating_out_of_range_rejected(customer, admin, on_sale_sku):
    """边界值：rating=6（超出 1–5）→ 应 400。（缺陷 D-11：当前直接落库）"""
    order_id = _paid_shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=True)
    resp = customer.client.post(
        "/api/reviews",
        json={"orderItemId": _order_item_id(order_id), "rating": 6, "content": "越界评分"},
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_review_rating_non_numeric_rejected(customer, admin, on_sale_sku):
    """等价类：rating 非数字 → 应 400。（缺陷 D-12：当前 Integer.parseInt 抛异常 → 500）"""
    order_id = _paid_shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=True)
    resp = customer.client.post(
        "/api/reviews",
        json={"orderItemId": _order_item_id(order_id), "rating": "abc", "content": "类型错误"},
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P2
def test_unsupported_method_returns_405(admin):
    """异常：对不存在的 HTTP 方法（DELETE 仓库）应 405，不得 500。（缺陷 D-22）"""
    resp = admin.request("DELETE", "/admin/warehouses/1")
    assert resp.status_code == 405, (
        f"不支持的方法应 405，实际 {resp.status_code}（body={resp.text[:120]}）"
    )
