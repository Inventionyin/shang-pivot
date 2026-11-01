#!/usr/bin/env python3
"""从端点模型生成「全接口巡检」用例数据。

输入：api_testcases/artifacts/endpoint-model.json（135 端点真值模型）
输出：pytest_automation/data/endpoint_matrix.json

巡检维度（全部为「拒绝类/只读类」请求，不产生业务副作用）：
- security_anonymous      受保护端点匿名访问 → 401
- security_role           角色越权（会员 token 打管理端 / 管理端 token 打 C 端）→ 401/403
- path_param_type         路径参数传非数字 → 4xx（不得 500）
- malformed_json          截断 JSON 请求体 → 400（不得 500）
- empty_body              空对象请求体 → 不得 5xx
- list_boundary           GET 列表端点分页零值/负值/超大值 → 不得 5xx
- detail_not_found        GET 详情端点不存在 ID → 40400/40410

用法：
    python3 scripts/build_endpoint_matrix.py
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT.parent / "api_testcases" / "artifacts" / "endpoint-model.json"
OUT = ROOT / "data" / "endpoint_matrix.json"

# 端点模型未能提取请求体字段的认证端点（@Valid DTO 在控制器入参上），手工补齐
EXTRA_BODY_ENDPOINTS = {
    ("POST", "/admin/auth/login"),
    ("POST", "/admin/auth/refresh"),
    ("POST", "/api/auth/login"),
    ("POST", "/api/auth/phone"),
    ("POST", "/api/auth/refresh"),
    ("POST", "/api/auth/sms/send"),
}

# 不解析请求体的端点（如登出）：只做「空请求体不得 5xx」检查，不期待 400
EXTRA_NO_BODY_ENDPOINTS = {
    ("POST", "/api/auth/logout"),
}

# 需要必填查询参数、无法用通用参数做正向调用的端点（正向/大分页检查跳过，避免重复记录同一缺陷）
SKIP_POSITIVE = {
    ("GET", "/admin/dict-items"),
    ("GET", "/admin/dict-items/by-code"),
    ("GET", "/admin/files/view"),
    ("GET", "/api/files/view"),
}

# 风险分级（可被 data/endpoint_risk.yaml 覆盖）：写操作且属核心域 → P0；其余写操作/核心域读 → P1；辅助读 → P2
CORE_WRITE_PREFIXES = (
    "/api/orders", "/api/payment", "/api/cart", "/api/addresses", "/api/after-sales",
    "/api/reviews", "/api/auth", "/admin/orders", "/admin/products", "/admin/users",
    "/admin/roles", "/admin/permissions", "/admin/auth", "/admin/customers",
    "/admin/after-sales", "/admin/reviews",
)
CORE_READ_PREFIXES = (
    "/api/orders", "/api/payment", "/api/cart", "/api/addresses", "/api/after-sales",
    "/api/reviews", "/admin/orders", "/admin/products", "/admin/users", "/admin/customers",
)

ROLE_BY_AUTH = {
    "管理员 token": "admin",
    "会员 token": "customer",
    "免认证（GET）": "none",
    "免认证": "none",
}


def _param_names(path: str) -> list[str]:
    return re.findall(r"\{([^}]+)\}", path)


def risk_of(method: str, path: str) -> str:
    if method != "GET":
        return "P0" if path.startswith(CORE_WRITE_PREFIXES) else "P1"
    return "P1" if path.startswith(CORE_READ_PREFIXES) else "P2"


def _sibling_list(path: str) -> str | None:
    """详情端点 → 同级列表端点（/api/orders/{id} → /api/orders）。"""
    if "{" not in path:
        return None
    prefix = path[: path.find("{")].rstrip("/")
    return prefix or None


def build() -> list[dict]:
    endpoints = json.loads(MODEL.read_text(encoding="utf-8"))["endpoints"]
    cases: list[dict] = []
    known_paths = {(e["method"], e["path"]) for e in endpoints}

    for ep in endpoints:
        method = ep["method"]
        path = ep["path"]
        auth = ep["auth"]
        role = ROLE_BY_AUTH.get(auth, "none")
        protected = role != "none"
        has_path = bool(ep.get("has_path_param"))
        has_body = method in ("POST", "PUT") and (
            bool(ep.get("body_presence")) or (method, path) in EXTRA_BODY_ENDPOINTS
        )
        params = _param_names(path)

        def add(check: str, title: str, **extra):
            cases.append(
                {
                    "check": check,
                    "title": title,
                    "method": method,
                    "path": path,
                    "auth": role,
                    "summary": ep.get("summary", ""),
                    **extra,
                }
            )

        if protected:
            add("security_anonymous", "安全-匿名访问被拒")
        if role == "admin":
            add("security_role", "安全-会员 token 越权管理端被拒", token="customer")
        elif role == "customer":
            add("security_role", "安全-管理端 token 越权 C 端被拒", token="admin")

        if has_path:
            add(
                "path_param_type",
                "逆向-路径参数类型错误",
                path_params="abc",
                expect="4xx",
            )
            if method == "GET":
                add(
                    "detail_not_found",
                    "逆向-资源不存在",
                    path_params="999999999",
                    expect="4xx",
                )

        if has_body:
            add("malformed_json", "异常-畸形 JSON 请求体", expect="400")
            add("empty_body", "异常-空请求体", expect="not_5xx")
        elif (method, path) in EXTRA_NO_BODY_ENDPOINTS:
            add("empty_body", "异常-空请求体", expect="not_5xx")

        if method == "GET" and not has_path:
            add("list_boundary", "边界-分页零值/负值/超大值", expect="not_5xx")
            if (method, path) not in SKIP_POSITIVE and not path.endswith("/export"):
                add("positive_list", "功能正向-列表成功返回", expect="2xx")
                add("bulk_page", "大数据量-size=100 大分页", expect="2xx")

        if method == "GET" and has_path:
            sibling = _sibling_list(path)
            if sibling and ("GET", sibling) in known_paths:
                add(
                    "positive_detail",
                    "功能正向-列表取 ID 后查详情",
                    sibling=sibling,
                    id_key=_param_names(path)[0] if _param_names(path) else "id",
                    expect="2xx",
                )

    # 登录接口正向：管理端账号与演示会员各一条
    for method, path in (("POST", "/admin/auth/login"), ("POST", "/api/auth/login")):
        if (method, path) in known_paths:
            cases.append(
                {
                    "check": "positive_login",
                    "title": "功能正向-正确凭证登录成功",
                    "method": method,
                    "path": path,
                    "auth": "none",
                    "summary": "登录",
                    "expect": "2xx",
                }
            )

    for case in cases:
        case["risk"] = risk_of(case["method"], case["path"])


    return cases


def main() -> None:
    cases = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    from collections import Counter

    dist = Counter(case["check"] for case in cases)
    print(f"[done] {len(cases)} 条巡检用例 -> {OUT}")
    for check, count in dist.items():
        print(f"  {check}: {count}")


if __name__ == "__main__":
    main()
