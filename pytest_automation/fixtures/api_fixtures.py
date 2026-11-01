"""API 夹具：匿名客户端、隔离会员、管理员、商品 SKU、地址、购物车、订单。"""
from __future__ import annotations

import pytest

from config.settings import settings
from core.api_client import ApiClient
from core.logger import get_logger
from helpers.auth_helper import (
    add_to_cart,
    create_address,
    login_admin,
    new_customer,
    pick_sku,
    submit_order,
)
from helpers.data_factory import address_payload, order_payload

log = get_logger("fixtures")


@pytest.fixture(scope="session")
def anonymous():
    """无 Token 客户端，用于公开接口（商品、搜索、分类、首页）。"""
    client = ApiClient(settings.api_mall_base)
    yield client
    client.close()


@pytest.fixture(scope="session")
def admin():
    """管理端客户端（session 级登录一次）。未提供密码则整组用例 skip。"""
    if not settings.admin_password:
        pytest.skip(
            "未设置 ADMIN_PASSWORD：请在 pytest_automation/.env.local 填写，"
            "或通过环境变量注入（.env.local 已 gitignore）"
        )
    client = ApiClient(settings.api_admin_base)
    login_admin(client)
    yield client
    client.close()


@pytest.fixture
def customer():
    """每次用例一个全新会员；结束后清理待支付订单、地址与购物车。"""
    cust = new_customer()
    yield cust

    client = cust.client
    try:
        page = client.get("/api/orders", params={"status": "PENDING_PAY", "size": 50}).json()
        for order in (page.get("data") or {}).get("records", []):
            client.post(f"/api/orders/{order['id']}/cancel")
    except Exception as exc:  # noqa: BLE001 - 清理失败只记录，不掩盖用例结果
        log.warning("清理待支付订单失败：%s", exc)
    try:
        addresses = client.get("/api/addresses").json().get("data") or []
        for address in addresses:
            client.delete(f"/api/addresses/{address['id']}")
    except Exception as exc:  # noqa: BLE001
        log.warning("清理地址失败：%s", exc)
    try:
        cart = client.get("/api/cart").json().get("data") or {}
        for item in cart.get("items", []):
            client.delete(f"/api/cart/items/{item['cartItemId']}")
    except Exception as exc:  # noqa: BLE001
        log.warning("清理购物车失败：%s", exc)
    finally:
        client.close()


@pytest.fixture
def second_customer():
    """第二个隔离会员，用于越权（IDOR）用例。"""
    cust = new_customer()
    yield cust
    cust.client.close()


@pytest.fixture(scope="session")
def sku(anonymous):
    """一个有库存的 SKU 信息：spu_id / sku_id / price / stock。"""
    return pick_sku(anonymous)


@pytest.fixture
def address(customer):
    """为当前会员创建一条默认地址，结束后删除。"""
    address_id = create_address(customer.client, address_payload(customer.phone))
    yield address_id
    customer.client.delete(f"/api/addresses/{address_id}")


@pytest.fixture
def cart_item(customer, sku):
    """为当前会员加购一件商品，返回购物车项 ID。"""
    item = add_to_cart(customer.client, sku["sku_id"], 1)
    return str(item["cartItemId"])


@pytest.fixture
def order(customer, sku):
    """创建一笔待支付订单：地址 + 加购 + 下单。"""
    address_id = create_address(customer.client, address_payload(customer.phone))
    item = add_to_cart(customer.client, sku["sku_id"], 1)
    data = submit_order(
        customer.client,
        order_payload(address_id=address_id, cart_item_ids=[str(item["cartItemId"])]),
    )
    yield {
        "data": data,
        "order_id": str(data["orderId"]),
        "address_id": address_id,
        "cart_item_id": str(item["cartItemId"]),
        "sku": sku,
    }
