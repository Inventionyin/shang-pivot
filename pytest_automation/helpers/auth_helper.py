"""认证与购物业务辅助：登录、取商品 SKU、加购、下单。

关键动作包在 allure.step 里：报告时间线能看到「登录 → 加购 → 下单」的业务步骤，
而不是一串裸 HTTP 请求。
"""
from __future__ import annotations

from contextlib import nullcontext
from types import SimpleNamespace

import pytest

from config.settings import settings
from core.api_client import ApiClient
from core.assertions import ok
from helpers.data_factory import unique_phone

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None


def _step(name: str):
    return allure.step(name) if allure is not None else nullcontext()


def login_customer(client: ApiClient, phone: str, code: str = None) -> dict:
    with _step(f"登录会员 {phone}"):
        data = ok(
            client.post(
                "/api/auth/login",
                json={
                    "phone": phone,
                    "code": code or settings.sms_code,
                    "clientType": "web",
                },
            )
        )
    client.set_token(data["accessToken"])
    return data


def login_admin(client: ApiClient, username: str = None, password: str = None) -> dict:
    with _step("登录管理端"):
        data = ok(
            client.post(
                "/admin/auth/login",
                json={
                    "username": username or settings.admin_username,
                    "password": password if password is not None else settings.admin_password,
                },
            )
        )
    client.set_token(data["accessToken"])
    return data


def new_customer(phone: str = None) -> SimpleNamespace:
    """新建一个隔离会员：每次调用都是全新账号，写用例互不干扰。"""
    phone = phone or unique_phone()
    client = ApiClient(settings.api_mall_base)
    data = login_customer(client, phone)
    return SimpleNamespace(
        phone=phone,
        client=client,
        token=data["accessToken"],
        refresh_token=data.get("refreshToken", ""),
        expires_in=data.get("expiresIn"),
        info=data.get("customer") or {},
    )


def pick_sku(client: ApiClient, spu_id: str = "101") -> dict:
    """选一个在售、有库存的 SKU；并行时按 worker 分片（避免跨 worker 抢同一库存行）。"""
    with _step("选取在售且有库存的 SKU"):
        from helpers import parallel

        if parallel.should_shard():
            from helpers import db

            row = db.pick_on_sale_sku(min_available=1)
            if row:
                return {
                    "spu_id": str(row["spu_id"]),
                    "sku_id": str(row["sku_id"]),
                    "price": float(row["price"]),
                    "stock": int(row["available_stock"]),
                    "title": f"SKU {row['sku_id']}",
                }
        return _pick_sku(client, spu_id)


def _pick_sku(client: ApiClient, spu_id: str) -> dict:
    detail = ok(client.get(f"/api/products/{spu_id}"))
    candidates = [s for s in detail.get("skus", []) if int(s.get("availableStock") or 0) > 0]
    if candidates:
        sku = candidates[0]
        return {
            "spu_id": str(detail["spu"]["id"]),
            "sku_id": str(sku["skuId"]),
            "price": float(sku["price"]),
            "stock": int(sku["availableStock"]),
            "title": sku.get("title") or detail["spu"].get("name"),
        }

    page = ok(client.get("/api/products", params={"page": 1, "size": 50}))
    for row in page.get("records", []):
        detail = ok(client.get(f"/api/products/{row['spuId']}"))
        for sku in detail.get("skus", []):
            if int(sku.get("availableStock") or 0) > 0:
                return {
                    "spu_id": str(detail["spu"]["id"]),
                    "sku_id": str(sku["skuId"]),
                    "price": float(sku["price"]),
                    "stock": int(sku["availableStock"]),
                    "title": sku.get("title") or detail["spu"].get("name"),
                }
    pytest.fail(
        "演示环境没有可售库存的 SKU：这是商品/库存数据前置条件缺失，"
        "请检查商品是否上架、库存是否大于 0（不再静默 skip）"
    )


def add_to_cart(client: ApiClient, sku_id: str, quantity: int = 1) -> dict:
    with _step(f"加入购物车（SKU {sku_id} × {quantity}）"):
        return ok(
            client.post("/api/cart/items", json={"skuId": sku_id, "quantity": quantity})
        )


def create_address(client: ApiClient, payload: dict) -> str:
    with _step("创建收货地址"):
        return str(ok(client.post("/api/addresses", json=payload)))


def submit_order(client: ApiClient, payload: dict) -> dict:
    with _step("提交订单"):
        return ok(client.post("/api/orders", json=payload))
