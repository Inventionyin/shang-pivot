"""测试数据工厂：随机会员、地址、幂等键。"""
from __future__ import annotations

import random
import string
import time
import uuid


def unique_phone(prefix: str = "139") -> str:
    """生成 11 位手机号；演示环境任意手机号 + 固定验证码即可自动注册。"""
    suffix = "".join(random.choice(string.digits) for _ in range(11 - len(prefix)))
    return f"{prefix}{suffix}"


def unique_key(prefix: str = "auto") -> str:
    return f"{prefix}-{int(time.time() * 1000)}-{uuid.uuid4().hex[:6]}"


def address_payload(phone: str = None) -> dict:
    phone = phone or unique_phone()
    return {
        "name": "自动化测试",
        "phone": phone,
        "province": "北京市",
        "city": "北京市",
        "district": "朝阳区",
        "detail": f"自动化测试地址 {uuid.uuid4().hex[:6]}",
        "isDefault": True,
    }


def order_payload(address_id, cart_item_ids=None, sku_id=None, quantity=None) -> dict:
    payload = {
        "addressId": address_id,
        "idempotentKey": unique_key("order"),
    }
    if cart_item_ids is not None:
        payload["cartItemIds"] = cart_item_ids
    if sku_id is not None:
        payload["skuId"] = sku_id
        payload["quantity"] = quantity or 1
    return payload
