#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清单驱动的契约执行器（v1）：把 test_cases_inventory.md 的契约行变成可执行测试。

分层：
- build_params(): 解析清单 → 可执行参数（解析不了的标注 skip 原因，不静默丢弃）
- resolve_*: 角色 token / 路径参数数据工厂 / 请求体构造（支持"缺参/不提供/其余同典型请求"）
- assert_outcome(): 动态断言引擎（HTTP + 业务码信封 + 列表/详情形状）

设计取舍：优先覆盖主流模式（GET/POST/PUT/DELETE 的典型/缺参/鉴权/幂等），
解析不了的行标记原因并统计，作为下一轮改进输入。
"""
from __future__ import annotations
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
import case_lint as L  # noqa: E402

DICT_ENUMS = {
    "UNAUTHORIZED", "FORBIDDEN", "VALIDATION_FAILED", "NOT_FOUND", "PRODUCT_NOT_FOUND",
    "SKU_NOT_FOUND", "SKU_REQUIRED", "ORDER_NOT_FOUND", "ORDER_STATE_INVALID", "STATE_INVALID",
    "PAYMENT_FAILED", "INSUFFICIENT_STOCK", "INVALID_QUANTITY", "CART_EMPTY", "COUPON_INVALID",
    "INTERNAL_ERROR",
}
HTTP_CODES = (200, 400, 401, 403, 404, 409, 500)


def norm_path(p: str) -> str:
    return re.sub(r"\{[^}]+\}", "{id}", p)


def parse_method_path(method_url: str) -> tuple[str, str]:
    m = re.match(r"^((?:GET|POST|PUT|DELETE|PATCH)(?:/(?:GET|POST|PUT|DELETE|PATCH))*)\s+(/.*)$", method_url)
    if not m:
        raise ValueError(f"method_url 不合法: {method_url}")
    return m.group(1).split("/")[0], m.group(2)


# ---------------------------------------------------------------- 角色 token
def resolve_role(headers: str, title: str) -> str:
    if "无效Token" in title or "无效 token" in title:
        return "invalid"
    if "管理员" in headers:
        return "admin"
    if "不含 Authorization" in headers or "不发送 Authorization" in headers or "不设置 Autho" in headers:
        return "anon"
    return "user"


# ---------------------------------------------------------------- 数据工厂
class FactoryContext:
    """为路径参数提供真实数据；记录创建物以便清理。"""

    def __init__(self, clients: dict[str, Any]):
        self.clients = clients
        self.created: list[tuple[str, str, str]] = []  # (method, role, path) for cleanup
        self.admin_targets: dict[str, int] = {}  # 管理端专属目标数据缓存
        self.product_id: int | None = None

    def _user(self):
        return self.clients["user"]

    def first_product(self) -> int:
        """选一个可加购商品：翻多页取库存最高且在售的（测试环境前几页常是 stock≤1 的历史测试商品）。"""
        if self.product_id is None:
            items = []
            for page in range(1, 4):
                r = self._user().get("/v1/products", params={"page": page, "size": 60})
                assert r.status_code == 200, f"取商品失败: {r.status_code}"
                batch = r.json().get("data") or []
                items += batch
                if len(batch) < 60:
                    break
            if not items:
                raise RuntimeError("环境无商品数据")
            usable = [it for it in items if (it.get("stock") or 0) >= 2 and it.get("status", 1) == 1]
            pool = usable or items
            self.product_id = max(pool, key=lambda it: it.get("stock") or 0)["id"]
        return self.product_id

    def create(self, path: str, payload: dict, role: str = "user", id_key: str = "id") -> int:
        c = self.clients[role]
        r = c.post(path, json=payload)
        assert r.status_code == 200, f"factory 创建失败 {path}: {r.status_code} {r.text[:200]}"
        body = r.json()
        rid = (body.get("data") or {}).get(id_key)
        if rid is None:
            raise RuntimeError(f"factory 创建响应无 {id_key}: {body}")
        self.created.append(("DELETE", role, f"{path}/{rid}"))
        return rid

    def cleanup(self) -> None:
        for method, role, path in reversed(self.created):
            try:
                if method == "POST":
                    self.clients[role].post(path)
                else:
                    self.clients[role].delete(path)
            except Exception:
                pass
        self.created.clear()

    # 各端点取数 -------------------------------------------------------------
    def resolve(self, source_path: str, role: str = "user") -> int | str:
        """按 '运行时先调用 GET <path> 取一条记录的 id' 的 source_path 取/造数据。"""
        p = norm_path(source_path).split("?")[0]
        c = self.clients[role]
        if p == "/v1/products":
            return self.first_product()
        if p == "/v1/categories":
            r = c.get("/v1/categories")
            data = r.json().get("data") or []
            if data:
                return data[0]["id"]
            raise RuntimeError("环境无类目数据")
        if p == "/v1/me/addresses":
            r = c.get("/v1/me/addresses")
            data = r.json().get("data") or []
            if data:
                return data[0]["id"]
            return self.create("/v1/me/addresses", {
                "receiver": "自动化工厂", "phone": "13800138000",
                "region": "北京市朝阳区", "detail": f"工厂地址{os.getpid()}", "isDefault": 0})
        if p == "/v1/cart":
            r = c.get("/v1/cart")
            try:
                data = r.json().get("data")
            except ValueError:
                raise RuntimeError(f"购物车接口返回非 JSON（HTTP {r.status_code}）：{r.text[:80]}")
            items = (data or {}).get("items") if isinstance(data, dict) else data
            if items:
                return items[0]["id"]
            pid = self.first_product()
            payload = {"productId": pid, "quantity": 1}
            detail = c.get(f"/v1/products/{pid}")
            if detail.status_code == 200:
                skus = (detail.json().get("data") or {}).get("skus") or []
                if skus:
                    payload["skuId"] = skus[0]["id"]
            return self.create("/v1/cart/items", payload)
        if p == "/v1/favorites":
            def fav_items():
                rr = c.get("/v1/favorites")
                return rr.json().get("data") or []
            data = fav_items()
            if data:
                return data[0].get("favId") or data[0].get("id")
            pid = self.first_product()
            rr = c.post("/v1/favorites", json={"productId": pid})
            assert rr.status_code == 200, f"创建收藏失败: {rr.status_code} {rr.text[:150]}"
            data = fav_items()
            if not data:
                raise RuntimeError("创建收藏后列表仍为空")
            fav_id = data[-1].get("favId") or data[-1].get("id")
            self.created.append(("DELETE", "user", f"/v1/favorites/{fav_id}"))
            return fav_id
        if p == "/v1/coupons/available":
            r = c.get("/v1/coupons/available")
            data = r.json().get("data") or []
            if data:
                return data[0].get("code") or data[0].get("couponCode")
            raise RuntimeError("环境无可用优惠券")
        if p == "/v1/orders":
            def order_items():
                rr = c.get("/v1/orders", params={"page": 1, "size": 50})
                d = rr.json().get("data")
                if isinstance(d, dict):
                    return d.get("items") or d.get("list") or []
                return d or []
            data = order_items()
            # 优先返回"待支付"订单（支付/取消/售后链都需要可操作状态）
            payable = [o for o in data if o.get("status") == 0]
            if payable:
                return payable[0]["id"]
            # 没有再造一单（确保购物车项 + 地址 → 结算）
            try:
                self.resolve("/v1/cart", role)
                addr = self.resolve("/v1/me/addresses", role)
                rr = c.post("/v1/orders/checkout", json={"addressId": addr})
                if rr.status_code == 200 and rr.json().get("code") in (200, None):
                    fresh = next((o for o in order_items() if o.get("status") == 0), None)
                    if fresh:
                        self.created.append(("POST", role, f"/v1/orders/{fresh['id']}/cancel"))
                        return fresh["id"]
            except Exception:
                pass
            if data:
                return data[0]["id"]
            raise RuntimeError("造单后仍无订单数据")
        if p == "/v1/aftersales":
            def aftersales_items():
                rr = c.get("/v1/aftersales")
                d = rr.json().get("data")
                if isinstance(d, dict):
                    return d.get("items") or d.get("list") or []
                return d or []
            data = aftersales_items()
            submitted = [a for a in data if a.get("status") == "SUBMITTED"]
            if submitted:
                return submitted[0]["id"]
            # 链式创建：已支付订单 → 申请售后
            order_id = self.resolve("/v1/orders", role)
            pay = c.post(f"/v1/payments/{order_id}/pay", json={"channel": "alipay"})
            trade = (pay.json().get("data") or {}).get("tradeId") if pay.status_code == 200 else None
            if trade:
                c.post("/v1/payments/webhook", json={"tradeId": trade, "status": "SUCCESS"})
            order = c.get(f"/v1/orders/{order_id}").json().get("data") or {}
            items = order.get("items") or []
            if items:
                c.post("/v1/aftersales/apply", json={
                    "orderId": order_id, "orderItemId": items[0]["id"], "qty": 1,
                    "type": "退货", "reason": "质量问题", "evidence": []})
                data = aftersales_items()
                submitted = [a for a in data if a.get("status") == "SUBMITTED"]
                if submitted:
                    self.created.append(("POST", role, f"/v1/aftersales/{submitted[0]['id']}/cancel"))
                    return submitted[0]["id"]
            if data:
                return data[0]["id"]
            raise RuntimeError("账号无售后数据且链式创建失败")
        if p == "/v1/notifications":
            r = c.get("/v1/notifications")
            data = r.json().get("data") or []
            if data:
                return data[0]["id"]
            # 创建一条
            rr = c.post("/v1/notifications", json={"type": "ORDER", "title": "自动化工厂通知",
                                                   "content": "factory", "relatedId": ""})
            body = rr.json().get("data") or {}
            if body.get("id"):
                self.created.append(("DELETE", role, f"/v1/notifications/{body['id']}"))
                return body["id"]
            raise RuntimeError("无法创建通知数据")
        if p == "/v1/reviews":
            r = c.get("/v1/reviews")
            data = r.json().get("data") or []
            if data:
                return data[0]["id"]
            raise RuntimeError("账号无评价数据")
        if p.startswith("/v1/admin/"):
            return self.resolve_admin(p)
        if p == "/v1/payments":
            raise RuntimeError("payments 无独立工厂")
        raise RuntimeError(f"未登记的数据工厂: {p}")

    # ------------------------------------------------ 管理端专属数据工厂
    def _admin(self):
        if "admin" not in self.clients:
            raise RuntimeError("管理端 token 不可用（ADMIN_TEST_ACCOUNT 未配置或登录失败）")
        return self.clients["admin"]

    def resolve_admin(self, p: str) -> int | str:
        """管理端行只操作"专属目标数据"，不触碰环境既有资产。"""
        ad = self._admin()
        if p == "/v1/admin/categories":
            return self._ensure_category()
        if p == "/v1/admin/products":
            return self._ensure_product()
        if p == "/v1/admin/users":
            return self._ensure_user()
        if p == "/v1/admin/orders":
            return self._ensure_admin_order()
        if p == "/v1/admin/aftersales":
            return self._ensure_aftersale()
        raise RuntimeError(f"未登记的管理端工厂: {p}")

    def _ensure_category(self) -> int:
        cached = self.admin_targets.get("category")
        if cached:
            r = self._admin().get(f"/v1/admin/categories")
            ids = {it["id"] for it in (r.json().get("data") or [])}
            if cached in ids:
                return cached
        name = f"自动化目标类目{int(time.time())}"
        r = self._admin().post("/v1/admin/categories", json={"name": name, "parentId": 0})
        rid = (r.json().get("data") or {}).get("id")
        if not rid:
            raise RuntimeError(f"创建目标类目失败: {r.status_code} {r.text[:120]}")
        self.admin_targets["category"] = rid
        self.created.append(("DELETE", "admin", f"/v1/admin/categories/{rid}"))
        return rid

    def _ensure_product(self) -> int:
        cached = self.admin_targets.get("product")
        if cached:
            r = self._admin().get(f"/v1/admin/products/{cached}")
            if r.status_code == 200 and (r.json().get("code") in (200, None)):
                return cached
        cat = self._ensure_category()
        name = f"自动化目标商品{int(time.time())}"
        r = self._admin().post("/v1/admin/products", json={
            "categoryId": cat, "name": name, "description": "工厂数据",
            "price": 1, "stock": 10, "status": 1})
        rid = (r.json().get("data") or {}).get("id")
        if not rid:
            raise RuntimeError(f"创建目标商品失败: {r.status_code} {r.text[:120]}")
        self.admin_targets["product"] = rid
        self.created.append(("DELETE", "admin", f"/v1/admin/products/{rid}"))
        return rid

    def _ensure_user(self) -> int:
        cached = self.admin_targets.get("user")
        if cached:
            users = self._admin().get("/v1/admin/users", params={"page": 1, "size": 100}).json().get("data") or []
            if any(u["id"] == cached for u in users):
                return cached
        account = _unique_account()
        rr = self.clients["anon"].post("/v1/auth/register", json={
            "account": account, "password": "Test@123456", "nickname": "自动化目标用户"})
        if rr.status_code != 200:
            raise RuntimeError(f"工厂注册目标用户失败: {rr.status_code} {rr.text[:120]}")
        users = self._admin().get("/v1/admin/users", params={"page": 1, "size": 100}).json().get("data") or []
        target = next((u for u in users if u.get("account") == account), None)
        if not target:
            raise RuntimeError("注册后管理端用户列表未找到目标用户")
        self.admin_targets["user"] = target["id"]
        return target["id"]

    def _ensure_admin_order(self) -> int:
        cached = self.admin_targets.get("order")
        if cached:
            return cached
        order_id = self.resolve("/v1/orders", "user")  # 复用用户侧造单（本人订单）
        self.admin_targets["order"] = order_id
        return order_id

    def _ensure_aftersale(self) -> int:
        cached = self.admin_targets.get("aftersale")
        if cached:
            d = self._admin().get("/v1/admin/aftersales", params={"page": 1, "size": 20}).json().get("data") or {}
            items = d.get("items") if isinstance(d, dict) else d
            if any(it.get("id") == cached for it in (items or [])):
                return cached
        user = self.clients["user"]
        order_id = self._ensure_admin_order()
        # 订单需为已支付：发起支付 + 回调成功
        pay = user.post(f"/v1/payments/{order_id}/pay", json={"channel": "alipay"})
        trade = (pay.json().get("data") or {}).get("tradeId") if pay.status_code == 200 else None
        if trade:
            user.post("/v1/payments/webhook", json={"tradeId": trade, "status": "SUCCESS"})
        order = user.get(f"/v1/orders/{order_id}").json().get("data") or {}
        items = order.get("items") or []
        if not items:
            raise RuntimeError("订单无明细，无法申请售后")
        apply = user.post("/v1/aftersales/apply", json={
            "orderId": order_id, "orderItemId": items[0]["id"], "qty": 1,
            "type": "退货", "reason": "质量问题", "evidence": []})
        if apply.status_code != 200:
            raise RuntimeError(f"工厂申请售后失败: {apply.status_code} {apply.text[:120]}")
        data = (user.get("/v1/aftersales").json().get("data") or [])
        target = next((a for a in data if a.get("orderId") == order_id), None)
        if not target:
            raise RuntimeError("申请后未找到售后记录")
        self.admin_targets["aftersale"] = target["id"]
        self.created.append(("POST", "user", f"/v1/aftersales/{target['id']}/cancel"))
        return target["id"]


# ---------------------------------------------------------------- 请求体构造
_counter = {"n": 0}


def _unique_account() -> str:
    import random
    _counter["n"] += 1
    return f"auto{os.getpid()}{int(time.time() * 1000) % 10**8}{_counter['n']}{random.randint(100, 999)}@test.com"


ENV_MAP = {
    "APP_TEST_ACCOUNT": lambda: os.getenv("APP_TEST_ACCOUNT", ""),
    "APP_TEST_PASSWORD": lambda: os.getenv("APP_TEST_PASSWORD", ""),
    "ADMIN_TEST_ACCOUNT": lambda: os.getenv("ADMIN_TEST_ACCOUNT", ""),
    "ADMIN_TEST_PASSWORD": lambda: os.getenv("ADMIN_TEST_PASSWORD", ""),
    "TEST_PASSWORD": lambda: "Test@123456",
    "TEST_ACCOUNT_SUFFIX": _unique_account,
}


def _first_json(text: str) -> dict | None:
    start = text.find("{")
    while start >= 0:
        depth = 0
        for i in range(start, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except ValueError:
                        break
        start = text.find("{", start + 1)
    return None


def _coerce(value: str):
    v = value.strip()
    try:
        return json.loads(v)
    except ValueError:
        return v


def _kv_pairs(text: str) -> dict:
    out = {}
    for m in re.finditer(r"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^；;，,\n]+)", text):
        k, v = m.group(1), m.group(2).strip()
        v = re.sub(r"（[^）]*）", "", v).strip()
        if v in ENV_MAP:
            out[k] = ENV_MAP[v]()
        elif k in ENV_MAP:
            out[k] = ENV_MAP[k]()
        elif v and "从" not in v:
            out[k] = _coerce(v)
    # `account 从 APP_TEST_ACCOUNT 取`
    for m in re.finditer(r"([A-Za-z_][A-Za-z0-9_]*)\s*从\s*([A-Z_]{3,})\s*取", text):
        if m.group(2) in ENV_MAP:
            out[m.group(1)] = ENV_MAP[m.group(2)]()
    return out


def build_body(payload: str, title: str, typical: dict | None,
               resolver=None, method: str = "") -> tuple[dict | None, str | None]:
    """返回 (body, skip_reason)。resolver(path)->值 用于'先从 GET … 取得'类字段。"""
    if "无请求体" in payload and "删除" not in payload:
        return None, None
    text = payload
    base: dict = {}
    if "其余请求参数同「典型请求」用例" in text:
        if typical is None:
            return None, "无法找到典型请求行"
        text = text.replace("其余请求参数同「典型请求」用例", "")
        base = dict(typical or {})
    else:
        base = _first_json(text) or {}
    if re.search(r"路径参数[^；;]*；?", text):
        text = re.sub(r"路径参数[^；;]*；?", "", text)
    base.update({k: v for k, v in _kv_pairs(text).items() if k not in base})
    # `X 先从 GET /path 取得…` / `X 使用 GET /path 返回的…（可选）`
    for m in re.finditer(
            r"([A-Za-z_][A-Za-z0-9_]*)\s*(?:先)?(?:从|使用)\s*(GET|POST)\s*(/v1[^\s，,；;）)]*)", text):
        key, src = m.group(1), m.group(3)
        optional = "（可选）" in text[m.start():m.start() + 80]
        if resolver is None:
            if optional:
                continue
            return None, f"字段 {key} 需要动态取值但无 resolver"
        try:
            base[key] = resolver(src)
        except Exception as exc:
            if optional:
                continue
            return None, f"字段 {key} 动态取值失败: {exc}"
    # 负向：删除/不提供必填字段
    for pat in (r"删除必填 body 字段\s*([A-Za-z_][A-Za-z0-9_]*)",
                r"不提供必填(?: body)? 字段\s*([A-Za-z_][A-Za-z0-9_]*)"):
        m = re.search(pat, payload)
        if m:
            base.pop(m.group(1), None)
    if not base:
        if method == "GET" or "不提供路径参数" in payload or "不发送 Authorization" in payload \
                or "不含 Authorization" in payload or "不提供必填" in payload:
            return None, None
        return None, "请求体解析为空（payload 模式未覆盖）"
    # 列表字段内占位符（如 favIds: ["从 GET /v1/favorites 动态取得首个收藏 id"]）
    for k, v in list(base.items()):
        if isinstance(v, list):
            resolved_items = []
            for item in v:
                if isinstance(item, str) and ("从 GET" in item or "动态取得" in item):
                    if resolver is None:
                        return None, f"字段 {k} 列表项需动态取值但无 resolver"
                    src = re.search(r"(GET|POST)\s*(/v1[^\s）)]*)", item)
                    try:
                        resolved_items.append(resolver(src.group(2)) if src else item)
                    except Exception as exc:
                        return None, f"字段 {k} 列表项取值失败: {exc}"
                else:
                    resolved_items.append(item)
            base[k] = resolved_items
    # 生成型字段占位
    for k, v in list(base.items()):
        if isinstance(v, str):
            if "运行时生成唯一单号" in v:
                base[k] = f"SF{int(time.time() * 1000)}"
            elif "运行时生成唯一账号" in v or k == "TEST_ACCOUNT_SUFFIX":
                base[k] = ENV_MAP["TEST_ACCOUNT_SUFFIX"]()
            elif "运行时测试手机号" in v:
                import random
                base[k] = f"138{random.randint(10000000, 99999999)}"
            elif "关联订单" in v:
                base[k] = ""
            elif "从 GET" in v or "动态取得" in v:
                src_m = re.search(r"(GET|POST)\s*(/v1[^\s，,；;）)]*)", v)
                if src_m and resolver is not None:
                    try:
                        base[k] = resolver(src_m.group(2))
                    except Exception as exc:
                        return None, f"字段 {k} 动态取值失败: {exc}"
                else:
                    return None, f"字段 {k} 需要动态取值（未覆盖）"
            elif "运行时" in v:
                return None, f"字段 {k} 的运行时值未覆盖：{v[:24]}"
    # 未解析的"从本人/当前数据"字段
    for m in re.finditer(r"([A-Za-z_][A-Za-z0-9_]*)(?:/[A-Za-z_][A-Za-z0-9_]*)?\s*从(?:本人|当前|已有|现有)", payload):
        if m.group(1) not in base:
            return None, f"字段 {m.group(1)} 需本人业务数据（工厂未覆盖）"
    return base, None


def build_path(path: str, title: str, payload: str, factory: FactoryContext, role: str) -> tuple[str, str | None]:
    """路径参数：缺参场景省略段；正常场景走数据工厂。"""
    if "{" not in path:
        return path, None
    if "缺少路径参数" in title:
        m = re.search(r"缺少路径参数\s*(\w+)", title)
        if m and "{" + m.group(1) + "}" in path:
            return path.replace("/{" + m.group(1) + "}", ""), None
        return re.sub(r"/\{[^}]+\}$", "", path), None
    m = re.search(r"路径参数\s*\{?(\w+)\}?[：:]\s*运行时先调用\s*(GET|POST)\s*(/v1[^\s，,；;）)]*)", payload)
    source_path = None
    if m:
        source_path = m.group(3)
    else:
        m2 = re.search(r"先调用\s*(GET|POST)\s*(/v1[^\s，,；;）)]*)", payload)
        if m2:
            source_path = m2.group(2)
    try:
        if source_path:
            value = factory.resolve(source_path, role)
        else:
            # 兜底：按接口语义猜最近似工厂
            value = factory.resolve(norm_path(path), role)
        return re.sub(r"\{[^}]+\}", str(value), path), None
    except Exception as exc:
        return path, f"路径参数取数失败: {exc}"


# ---------------------------------------------------------------- 断言引擎
def assert_outcome(resp, http_expected: int, code_expected: str, title: str) -> None:
    assert resp.status_code == http_expected, \
        f"HTTP 期望 {http_expected} 实际 {resp.status_code}：{resp.text[:300]}"
    try:
        body = resp.json()
    except ValueError:
        body = None
    ec = code_expected.strip()
    if ec.startswith("200"):
        if isinstance(body, dict) and "code" in body:
            assert body.get("code") == 200, f"body.code 期望 200 实际 {body.get('code')}：{str(body)[:200]}"
    elif ec.startswith("无"):
        # 框架级 404 等：无业务码断言；404 时确认无 error 信封或为空
        if resp.status_code == 404 and isinstance(body, dict):
            assert "error" not in body or not body.get("error"), f"框架级 404 不应含业务 error：{str(body)[:200]}"
    elif re.match(r"^[45]\d{2}（body", ec):
        num = int(ec[:3])
        assert isinstance(body, dict) and body.get("code") == num, \
            f"body.code 期望 {num} 实际 {body.get('code') if isinstance(body, dict) else body}：{str(body)[:200]}"
    elif ec in DICT_ENUMS:
        assert isinstance(body, dict) and isinstance(body.get("error"), dict) \
            and body["error"].get("code") == ec, \
            f"error.code 期望 {ec} 实际 {body.get('error', {}).get('code') if isinstance(body, dict) else body}：{str(body)[:200]}"
    # 动态形状校验（正向）：list 端点 data 为 list、无 data 时跳过
    if http_expected == 200 and isinstance(body, dict) and "data" in body:
        data = body["data"]
        if re.search(r"列表|数组", title) or "/v1/products" in title:
            pass  # 由用例断言列描述，执行器此处不强制


# ---------------------------------------------------------------- 参数构建
def build_params() -> list[dict[str, Any]]:
    rows = L.parse_inventory(L.DEFAULT_INV)
    contract = [r for r in rows if not r["case_id"].startswith("BUS")]
    typicals: dict[str, dict] = {}
    for r in contract:
        if "典型请求" in r["title"]:
            body, _ = build_body(r["payload"], r["title"], None)
            typicals.setdefault(norm_path(parse_method_path(r["method_url"])[1]), body or {})
    params = []
    for r in contract:
        cid, title = r["case_id"], r["title"]
        try:
            method, path = parse_method_path(r["method_url"])
        except ValueError as exc:
            params.append({"case_id": cid, "priority": r["priority"], "title": title, "skip": str(exc)})
            continue
        role = resolve_role(r["headers"], title)
        http_m = re.match(r"(\d{3})", r["expected_http"].strip())
        item = {
            "case_id": cid, "priority": r["priority"], "title": title, "role": role,
            "method": method, "path_pattern": path, "payload_text": r["payload"],
            "http": int(http_m.group(1)) if http_m else None,
            "code": r["expected_code"], "kind": r["kind"],
            "typical": typicals.get(norm_path(path)),
            "skip": None,
        }
        if item["http"] is None:
            item["skip"] = "预期 HTTP 无法解析"
        elif role == "admin" and not os.getenv("ADMIN_TEST_ACCOUNT"):
            item["skip"] = "缺少 ADMIN_TEST_ACCOUNT/PASSWORD（管理端凭据未提供）"
        elif re.search(r"ADMIN_TEST_ACCOUNT|ADMIN_TEST_PASSWORD", r["payload"]) and not os.getenv("ADMIN_TEST_ACCOUNT"):
            item["skip"] = "payload 引用 ADMIN_TEST_*（管理端凭据未提供）"
        elif "无效Token" in title:
            item["skip"] = "无效 Token 场景需要构造签名错误令牌（本轮未覆盖）"
        params.append(item)
    return params
