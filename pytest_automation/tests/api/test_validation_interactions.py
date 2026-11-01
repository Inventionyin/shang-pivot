"""参数间联动校验：字段组合、跨字段匹配性、校验短路顺序。

三类关系（与单字段等价类互补，不重复单字段用例）：
1. **必填性联动**：A 字段合规、B 字段缺失/空白 → 应精确返回 400，不能 5xx；
2. **匹配性联动**：字段格式都合法但业务上不匹配（手机号格式对、验证码错/超长）→ 业务码 40101；
3. **判定顺序（短路）**：多个字段同时非法时，服务端先报哪个错——用例固化顺序，防止实现调整时静默改变契约。

每个用例的 id 即「字段组合」描述，Allure 标题会带出，例如：
`参数联动校验-登录［手机号缺失-验证码合规］`。
"""
from __future__ import annotations

import pytest

from core.assertions import error, ok
from helpers import db
from helpers.auth_helper import add_to_cart, create_address, submit_order
from helpers.data_factory import address_payload, order_payload, unique_key

pytestmark = [pytest.mark.api, pytest.mark.validation]

BAD_REQUEST = 40000
STOCK_INSUFFICIENT = 40901
PRODUCT_UNAVAILABLE = 40410
NOT_FOUND = 40400
CODE_INVALID = 40101


@pytest.fixture(scope="module")
def db_ready():
    if not db.available():
        pytest.skip("数据库不可达：联动用例需要构造已完成订单与查 order_item")
    return True


@pytest.fixture
def on_sale_sku(db_ready):
    row = db.pick_on_sale_sku(min_available=2)
    assert row, "数据库中找不到在售且有库存的 SKU"
    return row


def _shipped_order(customer, admin, sku_id: str, confirm: bool) -> str:
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


# ---------- 登录：手机号 × 验证码 × clientType 组合 ----------

LOGIN_CASES = [
    pytest.param(
        {"phone": "", "code": "123456", "clientType": "web"},
        400, BAD_REQUEST, None, id="手机号缺失-验证码合规",
    ),
    pytest.param(
        {"phone": "   ", "code": "123456", "clientType": "web"},
        400, BAD_REQUEST, None, id="手机号空白-验证码合规",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "", "clientType": "web"},
        400, BAD_REQUEST, None, id="手机号合规-验证码缺失",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "   ", "clientType": "web"},
        400, BAD_REQUEST, None, id="手机号合规-验证码空白",
    ),
    pytest.param(
        {"phone": "", "code": "", "clientType": "web"},
        400, BAD_REQUEST, None, id="手机号与验证码均缺失",
    ),
    pytest.param(
        {"phone": "123", "code": "123456", "clientType": "web"},
        400, BAD_REQUEST, "手机号格式", id="手机号格式非法-验证码合规",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "999999", "clientType": "web"},
        200, CODE_INVALID, "验证码", id="手机号合规-验证码不匹配",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "1234567", "clientType": "web"},
        200, CODE_INVALID, "验证码", id="手机号合规-验证码超长",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "123456", "clientType": "pc"},
        400, BAD_REQUEST, "clientType", id="手机号验证码合规-clientType非法",
    ),
    pytest.param(
        {"phone": "13900000009", "code": "123456"},
        400, BAD_REQUEST, None, id="手机号验证码合规-clientType缺失",
    ),
]


@pytest.mark.P0
@pytest.mark.parametrize("payload, http, code, keyword", LOGIN_CASES)
def test_login_parameter_interactions(anonymous, payload, http, code, keyword):
    """登录接口字段联动：必填缺失、格式非法、跨字段不匹配、枚举非法。"""
    resp = anonymous.post("/api/auth/login", json=payload)
    body = error(resp, code=code, http_status=http)
    if keyword:
        assert keyword in body["msg"], f"提示应包含 {keyword!r}，实际 {body['msg']!r}"


@pytest.mark.P1
def test_login_matching_fields_succeed(anonymous):
    """匹配性联动正向：已注册手机号 + 正确验证码 + 合法 clientType → 成功签发令牌。"""
    resp = anonymous.post(
        "/api/auth/login",
        json={"phone": "13900000009", "code": "123456", "clientType": "web"},
    )
    data = ok(resp)
    assert data["accessToken"]


# ---------- 管理端登录：用户名 × 密码 组合 ----------

ADMIN_LOGIN_CASES = [
    pytest.param(
        {"username": "", "password": "Admin@123"},
        400, BAD_REQUEST, "用户名不能为空", id="用户名为空-密码合规",
    ),
    pytest.param(
        {"username": "admin", "password": ""},
        400, BAD_REQUEST, "密码不能为空", id="用户名合规-密码为空",
    ),
    pytest.param(
        {"username": "", "password": ""},
        400, BAD_REQUEST, ("用户名不能为空", "密码不能为空"),
        id="用户名与密码均为空",
    ),
    pytest.param(
        {"username": "admin", "password": "wrong-pass"},
        200, CODE_INVALID, "账号或密码错误", id="用户名合规-密码错误",
    ),
    pytest.param(
        {"username": "no-such-user", "password": "Admin@123"},
        200, CODE_INVALID, "账号或密码错误", id="用户名不存在-密码合规",
    ),
    pytest.param(
        {"username": "13900000009", "password": "123456"},
        200, CODE_INVALID, "账号或密码错误", id="非管理员账号登录管理端被拒",
    ),
]


@pytest.mark.P1
@pytest.mark.serial
@pytest.mark.parametrize("payload, http, code, keyword", ADMIN_LOGIN_CASES)
def test_admin_login_parameter_interactions(anonymous, payload, http, code, keyword):
    """管理端登录：必填联动与凭证不匹配均需精确报错，不能 5xx。"""
    resp = anonymous.post("/admin/auth/login", json=payload)
    body = error(resp, code=code, http_status=http)
    if isinstance(keyword, tuple):
        # 多字段同时缺失时 Bean Validation 报告顺序不固定，允许任一必填提示
        assert any(k in body["msg"] for k in keyword), (
            f"提示应包含 {keyword} 之一，实际 {body['msg']!r}"
        )
    else:
        assert keyword in body["msg"]


# ---------- 收货地址：六字段必填联动 ----------

ADDRESS_REQUIRED_FIELDS = ["name", "phone", "province", "city", "district", "detail"]


@pytest.mark.P1
@pytest.mark.parametrize("missing_field", ADDRESS_REQUIRED_FIELDS)
def test_address_missing_one_field_while_others_valid(customer, missing_field):
    """必填性联动：仅一个字段缺失、其余全部合规 → 400「收货信息不完整」。"""
    payload = address_payload(customer.phone)
    payload.pop(missing_field)
    resp = customer.client.post("/api/addresses", json=payload)
    body = error(resp, BAD_REQUEST)
    assert "收货信息不完整" in body["msg"]


@pytest.mark.P2
def test_address_all_fields_missing(customer):
    """必填性联动：六个字段全缺失 → 400，不得 500。"""
    resp = customer.client.post("/api/addresses", json={})
    error(resp, BAD_REQUEST)


# ---------- 购物车：skuId × quantity 组合与判定顺序 ----------

@pytest.mark.P0
def test_cart_missing_sku_while_quantity_valid(customer):
    """联动：skuId 缺失、quantity 合规 → 应 400。（缺陷 D-13：当前 500 系统异常）"""
    resp = customer.client.post("/api/cart/items", json={"quantity": 1})
    error(resp, BAD_REQUEST)


@pytest.mark.P0
def test_cart_missing_quantity_while_sku_valid(customer, on_sale_sku):
    """联动：skuId 合规、quantity 缺失 → 应 400。（缺陷 D-15：当前 500 系统异常）"""
    resp = customer.client.post(
        "/api/cart/items", json={"skuId": on_sale_sku["sku_id"]}
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_cart_both_invalid_quantity_reported_first(customer):
    """判定顺序：skuId 不存在 + quantity=0 → 先报数量（400「数量须 ≥ 1」）。"""
    resp = customer.client.post(
        "/api/cart/items", json={"skuId": "99999999", "quantity": 0}
    )
    body = error(resp, BAD_REQUEST)
    assert "数量" in body["msg"]


@pytest.mark.P1
def test_cart_invalid_sku_with_valid_quantity(customer):
    """匹配性联动：quantity 合规，skuId 不存在 → 40410 商品不可用。"""
    resp = customer.client.post(
        "/api/cart/items", json={"skuId": "99999999", "quantity": 1}
    )
    error(resp, PRODUCT_UNAVAILABLE)


@pytest.mark.P1
def test_cart_valid_combination_succeeds(customer, on_sale_sku):
    """正向：skuId 合规 + quantity 合规 → 加购成功。"""
    item = add_to_cart(customer.client, str(on_sale_sku["sku_id"]), 1)
    assert item["cartItemId"]


# ---------- 下单：地址 × 商品 × 数量 × 幂等键 组合 ----------

@pytest.mark.P0
def test_order_missing_key_reported_before_invalid_address(customer, on_sale_sku):
    """判定顺序：幂等键缺失 + 地址非法 → 先报 idempotentKey（400）。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": "0",
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 1,
        },
    )
    body = error(resp, BAD_REQUEST)
    assert "idempotentKey" in body["msg"]


@pytest.mark.P1
def test_order_invalid_address_with_valid_sku(customer, on_sale_sku):
    """判定顺序：地址非法 + SKU/数量合规 → 先报地址（400「收货地址无效」）。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": "0",
            "skuId": str(on_sale_sku["sku_id"]),
            "quantity": 1,
            "idempotentKey": unique_key("order"),
        },
    )
    body = error(resp, BAD_REQUEST)
    assert "地址" in body["msg"]


@pytest.mark.P1
def test_order_no_items_and_no_sku_rejected(customer, address):
    """联动：地址合规但既无购物车项也无 skuId → 400「请选择购物车项或立即购买 SKU」。"""
    resp = customer.client.post(
        "/api/orders",
        json={"addressId": address, "idempotentKey": unique_key("order")},
    )
    body = error(resp, BAD_REQUEST)
    assert "购物车项" in body["msg"] or "SKU" in body["msg"]


@pytest.mark.P1
def test_order_valid_sku_but_quantity_zero(customer, address, on_sale_sku):
    """联动：地址/SKU 合规、数量为 0 → 400「数量须 ≥ 1」。"""
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


@pytest.mark.P1
def test_order_valid_quantity_but_unknown_sku(customer, address):
    """匹配性联动：地址/数量合规、SKU 不存在 → 40410 商品不可用。"""
    resp = customer.client.post(
        "/api/orders",
        json={
            "addressId": address,
            "skuId": "99999999",
            "quantity": 1,
            "idempotentKey": unique_key("order"),
        },
    )
    error(resp, PRODUCT_UNAVAILABLE)


@pytest.mark.P1
def test_order_valid_combination_succeeds(customer, address, on_sale_sku):
    """正向：地址 + SKU + 数量 + 幂等键全合规 → 创建待支付订单。"""
    data = submit_order(
        customer.client,
        order_payload(
            address_id=address, sku_id=str(on_sale_sku["sku_id"]), quantity=1
        ),
    )
    assert data["status"] == "PENDING_PAY"
    ok(customer.client.post(f"/api/orders/{data['orderId']}/cancel"))


# ---------- 售后：订单 × 类型 × 原因 组合与判定顺序 ----------

@pytest.mark.P1
def test_after_sale_required_fields_reported_before_type(customer, admin, on_sale_sku):
    """判定顺序：type 非法 + reason 缺失 → 先报必填（400「订单、类型、原因必填」）。"""
    order_id = _shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=False)
    resp = customer.client.post(
        "/api/after-sales", json={"orderId": order_id, "type": "BAD_TYPE"}
    )
    body = error(resp, BAD_REQUEST)
    assert "必填" in body["msg"]


@pytest.mark.P1
def test_after_sale_valid_order_but_invalid_type(customer, admin, on_sale_sku):
    """匹配性联动：订单合规、type 枚举外 → 400「type 无效」。"""
    order_id = _shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=False)
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": order_id, "type": "BAD_TYPE", "reason": "x"},
    )
    body = error(resp, BAD_REQUEST)
    assert "type 无效" in body["msg"]


@pytest.mark.P1
def test_after_sale_valid_type_but_blank_reason(customer, admin, on_sale_sku):
    """联动：订单/类型合规、原因为空白 → 400 必填。"""
    order_id = _shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=False)
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": order_id, "type": "REFUND", "reason": "   "},
    )
    body = error(resp, BAD_REQUEST)
    assert "必填" in body["msg"]


@pytest.mark.P1
def test_after_sale_unknown_order_with_valid_fields(customer):
    """匹配性联动：type/reason 合规、订单不存在 → 40400，不得 400/500。"""
    resp = customer.client.post(
        "/api/after-sales",
        json={"orderId": "999999", "type": "REFUND", "reason": "x"},
    )
    error(resp, NOT_FOUND)


# ---------- 评价：订单项 × 评分 组合 ----------

@pytest.mark.P0
def test_review_valid_item_but_missing_rating(customer, admin, on_sale_sku):
    """联动：orderItemId 合规、rating 缺失 → 应 400。（缺陷 D-14：当前 500）"""
    order_id = _shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=True)
    resp = customer.client.post(
        "/api/reviews", json={"orderItemId": _order_item_id(order_id)}
    )
    error(resp, BAD_REQUEST)


@pytest.mark.P1
def test_review_unknown_item_with_valid_rating(customer):
    """匹配性联动：rating 合规、orderItemId 不存在 → 40400。"""
    resp = customer.client.post(
        "/api/reviews", json={"orderItemId": "999999999", "rating": 5}
    )
    error(resp, NOT_FOUND)


@pytest.mark.P1
def test_review_valid_combination_without_content(customer, admin, on_sale_sku):
    """正向：orderItemId 合规 + rating=5，content 可省略 → 评价成功。"""
    order_id = _shipped_order(customer, admin, str(on_sale_sku["sku_id"]), confirm=True)
    resp = customer.client.post(
        "/api/reviews",
        json={"orderItemId": _order_item_id(order_id), "rating": 5},
    )
    ok(resp)
