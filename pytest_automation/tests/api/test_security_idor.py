"""水平越权（IDOR）专项：A 的资源，B 的 token 一律不可读/不可改/不可删。

电商系统最高危的接口漏洞类型（OWASP API Top 1）。这些用例是回归护栏：
谁把「按 id 直查」写出作用域外（漏掉 customer_id 过滤），这里立即变红。

源码依据：
- 地址：AddressService.requireOwned → NOT_FOUND(40400)
- 购物车：CartService.requireOwnedItem → NOT_FOUND(40400)
- 订单：OrderSubmitService.confirmReceive / PaymentSandboxService.pay /
  OrderSubmitService.cancel 均先校验 customer_id → NOT_FOUND(40400)
"""
from __future__ import annotations

import pytest

from core.assertions import error, ok

pytestmark = [pytest.mark.api, pytest.mark.security]

NOT_FOUND = 40400


@pytest.mark.P0
def test_other_customer_cannot_cancel_order(customer, second_customer, order):
    """B 用他人订单号取消：拒绝，且订单状态保持 PENDING_PAY。"""
    resp = second_customer.client.post(f"/api/orders/{order['order_id']}/cancel")
    body = error(resp, NOT_FOUND)

    detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
    assert detail["order"]["status"] == "PENDING_PAY", (
        f"越权取消失败后订单状态被改动：{detail['order']['status']}"
    )
    assert body["msg"]


@pytest.mark.P0
def test_other_customer_cannot_pay_order(customer, second_customer, order):
    """B 替他人订单发起沙箱支付：拒绝，且订单仍未支付。"""
    resp = second_customer.client.post(
        "/api/payment/sandbox", json={"orderId": order["order_id"]}
    )
    error(resp, NOT_FOUND)

    detail = ok(customer.client.get(f"/api/orders/{order['order_id']}"))
    assert detail["order"]["status"] == "PENDING_PAY"


@pytest.mark.P0
def test_other_customer_cannot_update_address(customer, second_customer, address):
    """B 修改 A 的收货地址：拒绝，且地址内容未被篡改。"""
    payload = {
        "name": "越权篡改",
        "phone": second_customer.phone,
        "province": "广东省",
        "city": "深圳市",
        "district": "南山区",
        "detail": "越权写入的地址",
        "isDefault": True,
    }
    resp = second_customer.client.put(f"/api/addresses/{address}", json=payload)
    error(resp, NOT_FOUND)

    addresses = ok(customer.client.get("/api/addresses"))
    owned = {str(row["id"]): row for row in addresses}
    assert address in owned, "A 的地址在越权更新后消失了"
    assert owned[address]["name"] != "越权篡改", "越权更新竟然生效了"


@pytest.mark.P0
def test_other_customer_cannot_delete_address(customer, second_customer, address):
    """B 删除 A 的收货地址：拒绝，且地址仍存在。"""
    resp = second_customer.client.delete(f"/api/addresses/{address}")
    error(resp, NOT_FOUND)

    addresses = ok(customer.client.get("/api/addresses"))
    assert address in {str(row["id"]) for row in addresses}, "越权删除竟然生效了"


@pytest.mark.P1
def test_other_customer_cannot_set_default_address(customer, second_customer, address):
    """B 把 A 的地址设成默认：拒绝（跨账号写同一字段）。"""
    resp = second_customer.client.put(f"/api/addresses/{address}/default")
    error(resp, NOT_FOUND)


@pytest.mark.P0
def test_other_customer_cannot_modify_cart_item(customer, second_customer, cart_item):
    """B 修改 A 的购物车项数量：拒绝，且数量未被改动。"""
    resp = second_customer.client.put(
        f"/api/cart/items/{cart_item}", json={"quantity": 9}
    )
    error(resp, NOT_FOUND)

    cart = ok(customer.client.get("/api/cart"))
    item = next(
        (row for row in cart.get("items", []) if str(row["cartItemId"]) == cart_item),
        None,
    )
    assert item is not None, "越权修改后购物车项消失了"
    assert int(item["quantity"]) == 1, f"越权修改竟然生效：quantity={item['quantity']}"


@pytest.mark.P1
def test_other_customer_cannot_delete_cart_item(customer, second_customer, cart_item):
    """B 删除 A 的购物车项：拒绝，且项仍存在。"""
    resp = second_customer.client.delete(f"/api/cart/items/{cart_item}")
    error(resp, NOT_FOUND)

    cart = ok(customer.client.get("/api/cart"))
    assert cart_item in {str(row["cartItemId"]) for row in cart.get("items", [])}, (
        "越权删除购物车项竟然生效了"
    )
