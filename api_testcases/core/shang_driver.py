# -*- coding: utf-8 -*-
"""尚品汇（Shang Pivot）清单驱动执行器：把 test_cases_inventory.md 的契约行变成可执行请求。

设计要点
- 双端 base：管理端 `/admin/**` → ADMIN_BASE_URL；C 端 `/api/**` → MALL_BASE_URL（部署环境分端口）。
- 角色解析：按用例「请求头」列判定 anon / admin / user，登录一次缓存复用。
- 取值约定解析：把用例里可读的中文约定（"先调用 GET X 取一条记录的 id"）解析为真实值。
- 断言引擎：尚品汇约定 —— 成功码 `0`；业务失败族 HTTP 200 + body 数值码；未认证 401 + 40100。
"""
from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path
from typing import Any

import requests
from uuid import uuid4
from time import time as _time

ROOT = Path(__file__).resolve().parents[1]
def _bases() -> tuple[str, str]:
    """目标环境解析顺序：环境变量 → config/env/<TEST_ENV>.yaml → 部署演示地址。

    以前这里是硬编码部署地址，导致「不设环境变量就会打线上」，且 config/env/dev.yaml 的旧值
    （8080/api + MySQL）与本项目不符。现在统一走 settings，`TEST_ENV=demo` 可切到部署环境。
    """
    try:
        from config.settings import settings
        return settings.admin_base, settings.mall_base
    except Exception:
        return ("http://111.170.33.2:60085", "http://111.170.33.2:60084")


ADMIN_BASE, MALL_BASE = _bases()

# ErrorCode.java（源码）词条 → 数值码
ERROR_CODES = {
    "SUCCESS": 0, "BAD_REQUEST": 40000, "UNAUTHORIZED": 40100, "LOGIN_FAILED": 40101,
    "TOKEN_EXPIRED": 40102, "TOKEN_INVALID": 40103, "ACCOUNT_LOCKED": 40104,
    "FORBIDDEN": 40300, "CUSTOMER_FROZEN": 40310, "NOT_FOUND": 40400,
    "PRODUCT_UNAVAILABLE": 40410, "CONFLICT": 40900, "STOCK_INSUFFICIENT": 40901,
    "ORDER_STATUS_INVALID": 40902, "AFTER_SALE_STATUS_INVALID": 40903,
    "REVIEW_EXISTS": 40904, "SYSTEM_ERROR": 50000,
}


def base_for(path: str) -> str:
    return ADMIN_BASE if path.startswith("/admin/") else MALL_BASE


class Client:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.s.trust_env = False
        # 最近一次响应的头，供契约校验层比对 X-Trace-Id（call() 的返回值里放不下，避免破坏既有解包）
        self.last_headers: dict = {}

    def call(self, method: str, path: str, token: str | None = None,
             body: Any = None, params: dict | None = None, timeout: int = 20):
        url = base_for(path) + path
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
        r = self.s.request(method, url, headers=headers, params=params,
                           data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
                           timeout=timeout)
        self.last_headers = dict(r.headers)
        try:
            payload = r.json()
        except ValueError:
            payload = {"_raw": r.text[:200]}
        return r.status_code, payload, url

    def close(self):
        self.s.close()


# ---------------------------------------------------------------- 角色与登录
def login_admin(c: Client) -> tuple[str, str]:
    u, p = os.getenv("ADMIN_TEST_ACCOUNT", "admin"), os.getenv("ADMIN_TEST_PASSWORD", "Admin@123")
    st, body, _ = c.call("POST", "/admin/auth/login", body={"username": u, "password": p})
    d = (body.get("data") or {}) if st == 200 else {}
    return d.get("accessToken") or "", d.get("refreshToken") or ""


def login_customer(c: Client) -> tuple[str, str]:
    phone = os.getenv("APP_TEST_ACCOUNT", "13800000001")
    code = os.getenv("APP_TEST_SMS_CODE", "123456")
    st, body, _ = c.call("POST", "/api/auth/login",
                         body={"phone": phone, "code": code, "clientType": "web"})
    d = (body.get("data") or {}) if st == 200 else {}
    return d.get("accessToken") or "", d.get("refreshToken") or ""


# ------------------------------------------------------- 取值约定 → 可执行值
CALL_PAT = re.compile("先调用\\s+(GET|POST)\\s+(/\\S+?)\\s+取[^；<]*?([A-Za-z][A-Za-z0-9]*)\\s*(?=[；<,}\"]|$)")
ENV_PAT = re.compile(r"从环境变量\s+([A-Z_]+)\s+取")
REFRESH_PAT = re.compile(r"从登录响应\s+data\.refreshToken\s+取")


class Resolver:
    """把用例 payload 里的中文取值约定解析成真实值（失败返回 None，由调用方 skip 并给原因）。"""

    def __init__(self, client: Client, admin_token: str, user_token: str,
                 refresh_tokens: dict | None = None, other_token: str = ""):
        self.c = client
        self.tokens = {"admin": admin_token, "user": user_token, "other": other_token}
        self.refresh_tokens = refresh_tokens or {}
        self.cache: dict[str, Any] = {}

    def _order_spu(self, order_id: Any, token: str) -> str | None:
        st, b, _ = self.c.call("GET", f"/api/orders/{order_id}", token=token)
        items = ((b.get("data") or {}).get("items") or []) if st == 200 else []
        for it in items:
            if it.get("spuId") is not None:
                return str(it["spuId"])
        return None

    # 订单状态推进序（下单 → 支付 → 发货 → 收货）
    ORDER_STATES = ["PENDING_PAY", "PENDING_SHIP", "PENDING_RECEIVE", "COMPLETED"]

    def prepare_order(self, token_kind: str, target_state: str) -> tuple[Any, str]:
        """现场造一个处于目标状态的订单（含前置推进），返回 (orderId, 失败原因)。"""
        tok = self.tokens[token_kind]
        atok = self.tokens.get("admin", "")
        st, b, _ = self.c.call("GET", "/api/products", token=tok, params={"page": "1", "size": "5"})
        rows = (b.get("data") or {}).get("records") or []
        if not rows:
            return None, "无在售商品，无法造单"
        spu = rows[0]["spuId"]
        st, b, _ = self.c.call("GET", f"/api/products/{spu}", token=tok)
        skus = (b.get("data") or {}).get("skus") or []
        sku = next((x for x in skus if (x.get("availableStock") or 0) > 0), None)
        if not sku:
            return None, "无可用 SKU"
        st, b, _ = self.c.call("POST", "/api/orders", token=tok,
                               body={"addressId": 9101, "idempotentKey": f"AUTOTEST-{uuid4().hex[:12]}",
                                     "skuId": int(sku["skuId"]), "quantity": 1})
        if b.get("code") != 0:
            return None, f"造单失败：{str(b.get('msg'))[:60]}"
        data = b.get("data") or {}
        oid = data.get("orderId") or data.get("id")
        cur = data.get("status") or "PENDING_PAY"
        steps = {"PENDING_SHIP": "pay", "PENDING_RECEIVE": "ship", "COMPLETED": "confirm"}
        for state in self.ORDER_STATES[1:self.ORDER_STATES.index(target_state) + 1]:
            act = steps[state]
            if act == "pay":
                st, b, _ = self.c.call("POST", "/api/payment/sandbox", token=tok, body={"orderId": int(oid)})
            elif act == "ship":
                st, b, _ = self.c.call("POST", f"/admin/orders/{oid}/ship", token=atok,
                                       body={"trackingNo": f"AT{uuid4().hex[:10]}"})
            elif act == "confirm":
                st, b, _ = self.c.call("POST", f"/api/orders/{oid}/confirm", token=tok)
            if b.get("code") != 0:
                return None, f"推进到 {state} 失败：{str(b.get('msg'))[:60]}"
            cur = state
        return oid, ""

    def first_id(self, path: str, token_kind: str, want: str = "id",
                 consumer_path: str = "", filter_text: str = "") -> tuple[Any, str]:
        # 订单/订单项取值：不再从存量列表"挑状态"，直接现场造一个目标状态的订单
        if want in ("orderId", "orderItemId") and path.startswith("/api/orders"):
            ft = filter_text or ""
            target = None
            for cn, en in (("待支付", "PENDING_PAY"), ("待发货", "PENDING_SHIP"),
                           ("待收货", "PENDING_RECEIVE"), ("已完成", "COMPLETED")):
                if cn in ft:
                    target = en
                    break
            if target is None and consumer_path.endswith("/confirm"):
                target = "PENDING_RECEIVE"
            if target is None and consumer_path.endswith("/cancel"):
                target = "PENDING_PAY"
            if target is None and consumer_path.endswith("/sandbox"):
                target = "PENDING_PAY"
            if target is None and ft:
                fm = re.search(r"状态为\s*([A-Z_]{4,})", ft)
                target = fm.group(1) if fm else "COMPLETED"
            oid, err = self.prepare_order(token_kind, target or "PENDING_PAY")
            if err:
                return None, err
            if want == "orderId":
                return oid, ""
            st, det, _ = self.c.call("GET", f"/api/orders/{oid}", token=self.tokens[token_kind])
            items = ((det.get("data") or {}).get("items") or []) if st == 200 else []
            if items and items[0].get("id") is not None:
                return items[0]["id"], ""
            return None, "造单成功但取不到订单项"
        key = f"{token_kind}:{path}"
        if key in self.cache:
            return self.cache[key], ""
        base_path, _, query = path.partition("?")
        params = dict(kv.split("=", 1) for kv in query.split("&") if "=" in kv) if query else None
        if "{" in base_path:
            return None, f"取值约定指向需要路径参数的接口 {base_path}，未接链式造数"
        st, body, _ = self.c.call("GET", base_path, token=self.tokens[token_kind], params=params)
        if st != 200 or not isinstance(body, dict):
            return None, f"预取 {base_path} 失败：HTTP {st}"
        data = body.get("data")
        rows = None
        if isinstance(data, list):
            rows = data
        elif isinstance(data, dict):
            rows = data.get("records") or data.get("list") or data.get("items")
        if not rows:
            return None, f"预取 {base_path} 返回空列表"
        # 约定文本里的"状态为 X"直接作为过滤条件（BUS 用例常用中文状态名）
        CN_STATE = {"待支付": "PENDING_PAY", "待发货": "PENDING_SHIP", "待收货": "PENDING_RECEIVE",
                    "已完成": "COMPLETED", "已关闭": "CLOSED", "待审核": "PENDING_AUDIT",
                    "已驳回": "REJECTED", "退款中": "REFUNDING"}
        ft = filter_text or ""
        for cn, en in CN_STATE.items():
            if cn in ft:
                ft = ft + " 状态为 " + en
                break
        fm = re.search(r"状态为\s*([A-Z_]{4,})", ft)
        if fm and rows:
            st_rows = [r for r in rows if isinstance(r, dict) and r.get("status") == fm.group(1)]
            if not st_rows:
                return None, f"列表中没有状态为 {fm.group(1)} 的记录，用例无可执行目标"
            rows = st_rows
        # 他人会员订单：用第二个测试账号拉取其名下订单（越权类用例需要"非本人"资源）
        if "其他会员" in (filter_text or "") and base_path == "/api/orders":
            if not self.tokens.get("other"):
                ph, pc = os.getenv("APP_TEST_ACCOUNT_2", "13900000002"), os.getenv("APP_TEST_SMS_CODE", "123456")
                st2, b2, _ = self.c.call("POST", "/api/auth/login",
                                         body={"phone": ph, "code": pc, "clientType": "web"})
                self.tokens["other"] = ((b2.get("data") or {}).get("accessToken") or "") if st2 == 200 else ""
            if not self.tokens.get("other"):
                return None, "第二测试账号登录失败，无法取得他人会员订单（需 APP_TEST_ACCOUNT_2）"
            st2, b2, _ = self.c.call("GET", "/api/orders", token=self.tokens["other"],
                                     params={"page": "1", "size": "5"})
            orows = ((b2.get("data") or {}).get("records") or []) if st2 == 200 else []
            if not orows:
                return None, "第二测试账号名下无订单，越权用例无可执行目标"
            return orows[0].get("id"), ""
        # 未评价订单项：已完成订单的订单项中，排除已出现在商品评价列表里的 orderItemId
        if want == "orderItemId" and (consumer_path == "/api/reviews" or "未评价" in (filter_text or "")):
            done = [r for r in rows if isinstance(r, dict) and r.get("status") == "COMPLETED"]
            reviewed = set()
            for o in done:
                spu = self._order_spu(o.get("id"), self.tokens[token_kind])
                if not spu:
                    continue
                st2, b2, _ = self.c.call("GET", f"/api/products/{spu}/reviews",
                                         token=self.tokens[token_kind], params={"page": "1", "size": "50"})
                for rv in (((b2.get("data") or {}).get("records")) or []) if st2 == 200 else []:
                    if rv.get("orderItemId") is not None:
                        reviewed.add(str(rv["orderItemId"]))
            for o in done:
                st2, b2, _ = self.c.call("GET", f"/api/orders/{o['id']}", token=self.tokens[token_kind])
                for it in (((b2.get("data") or {}).get("items")) or []) if st2 == 200 else []:
                    iid = it.get("id")
                    if iid is not None and str(iid) not in reviewed:
                        self.cache[key] = iid
                        return iid, ""
            return None, "已完成订单的订单项均已评价，无可执行的未评价订单项"
        # 订单类场景：按用例意图挑选状态匹配、且无冲突的订单
        NEED_STATE = {"PENDING_PAY": "/api/orders/{id}/cancel", "PENDING_RECEIVE": "/api/orders/{id}/confirm"}
        if want == "orderId" and base_path == "/api/orders" and consumer_path in (
                "/api/orders/{id}/cancel", "/api/orders/{id}/confirm", "/api/payment/sandbox"):
            want_state = {"cancel": "PENDING_PAY", "confirm": "PENDING_RECEIVE",
                          "sandbox": "PENDING_PAY"}[consumer_path.rstrip("/").split("/")[-1].replace("{id}", "cancel") if consumer_path.endswith("/cancel") else consumer_path.rstrip("/").split("/")[-1]]
            want_state = "PENDING_PAY" if consumer_path.endswith(("/cancel", "/sandbox")) else "PENDING_RECEIVE"
            ok = [r for r in rows if isinstance(r, dict) and r.get("status") == want_state]
            if not ok:
                return None, f"该会员名下没有状态为 {want_state} 的订单，用例无可执行目标"
            rows = ok
        # 评价场景：需要"已完成订单的订单项"
        if want == "orderItemId" and base_path == "/api/orders":
            done = [r for r in rows if isinstance(r, dict) and r.get("status") == "COMPLETED"]
            if not done:
                return None, "该会员名下没有已完成订单，评价用例无可执行目标"
            st3, det, _ = self.c.call("GET", f"/api/orders/{done[0]['id']}", token=self.tokens[token_kind])
            items = (((det.get("data") or {}).get("items")) or []) if st3 == 200 else []
            if not items:
                return None, "已完成订单下无订单项，评价用例无可执行目标"
            return items[0].get("id"), ""
        # 售后场景：必须挑一个"没有进行中售后"的订单，否则必然 40000
        if want == "orderId" and base_path == "/api/orders":
            st2, open_body, _ = self.c.call("GET", "/api/after-sales", token=self.tokens[token_kind],
                                            params={"page": "1", "size": "50"})
            busy = set()
            if st2 == 200:
                d2 = open_body.get("data")
                rs = d2.get("records") if isinstance(d2, dict) else d2
                for a in (rs or []):
                    if a.get("orderId") is not None and a.get("status") in ("PENDING_AUDIT", "REFUNDING"):
                        busy.add(str(a["orderId"]))
            # 可申请售后的订单：状态为待收货/已完成，且无进行中售后
            free = [r for r in rows if isinstance(r, dict)
                    and str(r.get("id")) not in busy
                    and r.get("status") in ("PENDING_RECEIVE", "COMPLETED")]
            if not free:
                return None, "该会员名下没有「待收货/已完成且无进行中售后」的订单，售后用例无可执行目标"
            rows = free
        first = rows[0]
        if isinstance(first, dict):
            # 两跳优先：C 端列表只有 spuId，SKU 必须从详情取（否则会误用 spuId）
            if want in ("skuId", "skuids") and first.get("skuId") is None:
                spu = first.get("spuId") or first.get("id")
                st2, det, _ = self.c.call("GET", f"{base_path}/{spu}", token=self.tokens[token_kind])
                skus = ((det.get("data") or {}).get("skus") or []) if st2 == 200 else []
                for sk in skus:
                    if sk.get("skuId") is not None and (sk.get("availableStock") or 0) > 0:
                        self.cache[key] = sk["skuId"]
                        return sk["skuId"], ""
                return None, f"两跳取可售 SKU 失败：{base_path}/{spu}"
            for k in dict.fromkeys([want, "id", "spuId", "skuId", "code"]):
                if first.get(k) is not None:
                    self.cache[key] = first[k]
                    return first[k], ""
        return None, f"预取 {base_path} 的记录中取不到 id 字段"

    def resolve(self, text: str, token_kind: str, depth: int = 0,
                consumer_path: str = "") -> tuple[str, str]:
        """替换文本中的取值约定；返回 (替换后文本, 失败原因)。"""
        if depth > 4:
            return text, "取值约定嵌套过深"
        env = ENV_PAT.search(text)
        if env:
            val = os.getenv(env.group(1), "")
            if not val:
                return text, f"环境变量 {env.group(1)} 未设置"
            return self.resolve(text[:env.start()] + val + text[env.end():], token_kind, depth + 1,
                                consumer_path)
        ref = REFRESH_PAT.search(text)
        if ref:
            val = self.refresh_tokens.get(token_kind, "")
            if not val:
                return text, "缺少 refreshToken（会话初始化未取得）"
            return self.resolve(text[:ref.start()] + val + text[ref.end():], token_kind, depth + 1,
                                consumer_path)
        m = CALL_PAT.search(text)
        if not m:
            return text, ""
        path = m.group(2)
        kind = "admin" if path.startswith("/admin/") else token_kind
        val, err = self.first_id(path, kind, want=m.group(3), consumer_path=consumer_path,
                                 filter_text=text)
        if err:
            return text, err
        new = text[:m.start()] + str(val) + text[m.end():]
        return self.resolve(new, token_kind, depth + 1, consumer_path)


# ------------------------------------------------------------------ 用例解析
def parse_row(row: dict) -> dict:
    """把 14 列中的可用信息整理成执行参数。"""
    method_url = row["method_url"]
    method, _, path = method_url.partition(" ")
    return {
        "case_id": row["case_id"], "title": row["title"], "kind": row["kind"],
        "priority": row["priority"], "method": method.strip(), "path": path.strip(),
        "headers": row["headers"], "payload": row["payload"],
        "expected_http": row["expected_http"].strip(), "expected_code": row["expected_code"].strip(),
        "assertions": row["assertions"],
    }


def role_of(headers: str) -> str:
    if "不含 Authorization" in headers or "免认证" in headers:
        return "anon"
    if "管理员 token" in headers:
        return "admin"
    if "普通用户 token" in headers:
        return "user"
    return "anon"


def parse_query(payload: str) -> dict:
    m = re.search(r"查询参数：([^<]*)", payload)
    if not m:
        return {}
    out = {}
    for kv in re.split(r"[；&]", m.group(1)):
        kv = re.sub(r"（[^）]*）|其余.*$", "", kv).strip()
        if "=" in kv:
            k, v = kv.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def _field_types(method: str, path: str) -> dict[str, str]:
    """从端点模型取请求体字段的源码类型：{field: 'java.util.List<Long>'}。"""
    try:
        import json as _json
        model = _json.loads((ROOT / "artifacts" / "endpoint-model.json").read_text(encoding="utf-8"))
        for e in model.get("endpoints", []):
            if e["method"] == method and e["path"] == path:
                return {f.split(":", 1)[0]: f.split(":", 1)[1] for f in (e.get("dto_fields") or [])}
    except Exception:
        pass
    return {}


def _typed(value: Any, typ: str) -> Any:
    """按源码字段类型校正 JSON 取值（列表/数字），避免 String 塞进 List<Long> 导致反序列化 500。"""
    base = re.sub(r"<.*>", "", typ).strip().split(".")[-1] if typ else ""
    if base in ("List", "Set", "Collection"):
        items = value if isinstance(value, list) else [value]
        inner = typ[typ.find("<") + 1: typ.rfind(">")] if "<" in typ else "String"
        return [_typed(v, inner) for v in items]
    if base in ("Long", "Integer", "int", "long"):
        if isinstance(value, str) and re.match(r"^-?\d+$", value):
            return int(value)
        return value
    if base in ("BigDecimal", "Double", "Float", "double") and isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return value
    if base in ("Boolean", "boolean") and isinstance(value, str) and value in ("true", "false"):
        return value == "true"
    return value


def coerce_body(raw: str) -> tuple[Any, str]:
    """把请求体文本转成可发送的对象；支持严格 JSON 与"人类可读简写"两种写法。

    简写形态（业务用例常用）：`{skuId 先调用 GET /api/products 取详情中的 SKU id, quantity: 1}`
    → 顶层按逗号切分；每段按首个冒号（无冒号则按首个空格）分键值；值按需加引号。
    """
    try:
        return json.loads(raw), ""
    except ValueError:
        pass
    s = raw.strip()
    if not (s.startswith("{") and s.endswith("}")):
        return None, f"请求体无法解析：{raw[:70]}"
    inner = s[1:-1].strip()
    if not inner:
        return {}, ""
    obj = {}
    depth = 0
    part = ""
    parts = []
    for ch in inner:                      # 顶层逗号切分（忽略嵌套）
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(part); part = ""
        else:
            part += ch
    if part:
        parts.append(part)
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if ":" in p:
            k, v = p.split(":", 1)
        else:
            tokens = p.split(None, 1)
            if len(tokens) < 2:
                continue
            k, v = tokens[0], tokens[1]
        k, v = k.strip().strip('"'), v.strip()
        if not k:
            continue
        if v in ("true", "false", "null") or re.match(r"^-?\d+(\.\d+)?$", v) or v.startswith(("[", "{")):
            try:
                obj[k] = json.loads(v)
            except ValueError:
                obj[k] = v
        else:
            obj[k] = v.strip('"')
    return obj, ""


def parse_body_text(payload: str) -> str:
    m = re.search(r"请求体：(\{.*\}|\[.*?\])", payload)
    if m:
        return m.group(1).strip()
    if "提交空请求体" in payload:  # 缺参场景：显式提交 {}
        return "{}"
    s = payload.strip()
    if s.startswith("{") or s.startswith("["):   # BUS 用例：payload 直接就是 JSON 字面量
        return s
    return ""


def parse_path_conventions(payload: str) -> dict:
    """路径参数名 → 取值约定所在的整段文本。"""
    out = {}
    m = re.search(r"路径参数\s+([^：]+)：([^；<]*)", payload)
    if m:
        for name in re.split(r"[、,]", m.group(1)):
            if name.strip():
                out[name.strip()] = m.group(2).strip()
    return out


# -------------------------------------------------------------- 断言引擎
def check(case: dict, http: int, body: dict, second: tuple | None = None) -> tuple[bool, str]:
    """返回 (是否通过, 说明)。expected_code 支持：0 / 词典词条 / 无（框架级）。"""
    ec = case["expected_code"]
    want_http = int(re.match(r"(\d{3})", case["expected_http"]).group(1))
    got_http = second[0] if second else http
    got_body = second[1] if second else body
    if got_http != want_http:
        return False, f"HTTP 期望 {want_http} 实际 {got_http}｜body={str(got_body)[:120]}"
    if ec.startswith("0"):
        if got_body.get("code") != 0:
            return False, f"业务码期望 0 实际 {got_body.get('code')}｜{str(got_body.get('msg'))[:80]}"
        return True, "通过"
    if ec.startswith("无"):
        return True, "通过（框架级，无业务码）"
    term = re.sub(r"（.*?）", "", ec).strip()
    want = ERROR_CODES.get(term)
    if want is None:
        return False, f"未知业务码词条 {term}"
    if got_body.get("code") != want:
        return False, (f"业务码期望 {term}({want}) 实际 {got_body.get('code')}"
                       f"｜{str(got_body.get('msg'))[:80]}")
    return True, "通过"


def repeat_needed(case: dict) -> bool:
    return case["kind"] == "幂等"


def build_request(case: dict, res: Resolver, admin_token: str, user_token: str) -> tuple[dict, str]:
    """产出 {method,path,token,body,params}；失败返回原因。"""
    role = role_of(case["headers"])
    token = {"admin": admin_token, "user": user_token, "anon": None}[role]
    path = case["path"]
    payload = case["payload"]
    tk = "admin" if path.startswith("/admin/") else "user"

    # 路径参数
    conv = parse_path_conventions(payload)
    for name, text in conv.items():
        if "省略" in text:
            continue  # 缺参场景：执行器按"省略段"处理
        m = CALL_PAT.search(text)
        if m:  # 取值约定：直接取解析值（不做文本替换，避免"运行时"等前缀残留）
            kind = "admin" if m.group(2).startswith("/admin/") else tk
            want = "skuId" if re.search(r"sku", text, re.I) else name
            val, err = res.first_id(m.group(2), kind, want=want, consumer_path=path,
                                    filter_text=text)
            if err:
                return {}, err
        else:  # 环境变量 / 具体值
            val, err = res.resolve(text.strip(), tk)
            if err:
                return {}, err
            val = val.strip()
        path = path.replace("{" + name + "}", str(val))
        if "{" in path:
            return {}, f"路径参数 {name} 未解析出可执行值"

    # 缺路径参数场景：省略最后一段
    if "不提供路径参数" in payload:
        # 省略该路径段但保留边界斜杠：末尾 → /admin/users/（实测 404）；中段 → /admin/users//status（实测框架级 400）
        path = re.sub(r"/\{[^}]+\}", "/", path)

    # 请求体
    raw = parse_body_text(payload)
    body = None
    if raw:
        # 顺序很重要：先把请求体结构化，再逐字段解析取值。
        # 若先对整段文本做替换，散文式取值（如 `地址 id（先调用 GET X 取）`）会被替换打断 JSON 结构。
        body, perr = coerce_body(raw)
        if perr:
            return {}, perr
        if isinstance(body, dict):
            for k, v in list(body.items()):
                if isinstance(v, str) and ("先调用" in v or "从环境变量" in v or "从登录响应" in v):
                    m2 = CALL_PAT.search(v)
                    if m2:  # 值内嵌取值约定 → 以约定结果为整个值（丢弃包围的说明文字，与路径参数同策略）
                        kind2 = "admin" if m2.group(2).startswith("/admin/") else tk
                        want2 = "skuId" if re.search(r"sku", v, re.I) else m2.group(3)
                        nv, err = res.first_id(m2.group(2), kind2, want=want2,
                                               consumer_path=path, filter_text=v)
                    else:
                        nv, err = res.resolve(v, tk, consumer_path=path)
                    if err:
                        return {}, err
                    body[k] = nv
                elif isinstance(v, list):
                    out = []
                    for item in v:
                        if isinstance(item, str) and "先调用" in item:
                            nv, err = res.resolve(item, tk, consumer_path=path)
                            if err:
                                return {}, err
                            out.append(nv)
                        else:
                            out.append(item)
                    body[k] = out
        types = _field_types(case["method"], case["path"])
        if isinstance(body, dict) and types:
            body = {k: _typed(v, types.get(k, "")) for k, v in body.items()}
        # 幂等键必须每次执行唯一：固定值会命中历史订单（幂等契约返回旧单），导致后续状态断言全错
        if isinstance(body, dict) and "idempotentKey" in body:
            body["idempotentKey"] = "AUTOTEST-" + uuid4().hex[:12]
        body = _fill_conventions(body)

    params = parse_query(payload) or None
    if params:  # query 值里也可能有取值约定（如必填的 dictId）
        for k, v in list(params.items()):
            m = CALL_PAT.search(v)
            if m:
                kind = "admin" if m.group(2).startswith("/admin/") else tk
                val, err = res.first_id(m.group(2), kind, want=k)
                if err:
                    return {}, err
                params[k] = str(val)
    if params and "其余可选过滤参数" in payload and "size" not in params:
        params["size"] = "5"
    return {"method": case["method"], "path": path, "token": token,
            "body": body, "params": params}, ""


NUMERIC_KEY = re.compile(r"(size|bytes|qty|quantity|count|amount|price|sort|rating|level|stock)$", re.I)


def _fill_conventions(obj):
    """把仍带中文约定的字段值换成本地可执行值（id 类字段若未解析成功则报错由调用方处理）。"""
    if isinstance(obj, dict):
        return {k: _fill_conventions(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_fill_conventions(v) for v in obj]
    if isinstance(obj, str) and obj.startswith("自动化测试"):
        return obj
    return obj


# ------------------------------------------------------------ 专属造数链路
def family_of(path: str, ops: set[str]) -> dict | None:
    """资源族：`/admin/brands/{id}` → 可用 `POST /admin/brands` 造数、`DELETE .../{id}` 清理。"""
    if "{" not in path:
        return None
    root = re.sub(r"/\{[^}]+\}.*$", "", path)
    if not root:
        return None
    create, delete = f"POST {root}", f"DELETE {path}"
    if create in ops and delete in ops:
        return {"root": root, "create": ("POST", root), "delete": ("DELETE", path)}
    return None


def endpoint_ops() -> set[str]:
    import json as _json
    model = ROOT / "artifacts" / "endpoint-model.json"
    eps = _json.loads(model.read_text(encoding="utf-8"))["endpoints"]
    return {f"{e['method']} {e['path']}" for e in eps}
