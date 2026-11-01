#!/usr/bin/env python3
"""Build a deterministic, reviewable ZenTao case revision plan.

This script does not modify ZenTao.  It applies only documented/source-backed
values; unresolved behavior is represented by a manual-decision item instead
of an invented expected value.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.zentao_sync import parse_inventory  # noqa: E402


NAMES = {
    ("GET", "/v1/me"): "当前用户",
    ("GET", "/v1/me/addresses"): "收货地址列表",
    ("POST", "/v1/me/addresses"): "新增收货地址",
    ("PUT", "/v1/me/addresses/{id}"): "修改收货地址",
    ("DELETE", "/v1/me/addresses/{id}"): "删除收货地址",
    ("GET", "/v1/products"): "商品列表",
    ("POST", "/v1/products"): "创建商品",
    ("GET", "/v1/products/{id}"): "商品详情",
    ("GET", "/v1/categories"): "类目列表",
    ("GET", "/v1/cart"): "购物车列表",
    ("POST", "/v1/cart/items"): "加入购物车",
    ("PUT", "/v1/cart/items/{id}"): "修改购物车数量",
    ("DELETE", "/v1/cart/items/{id}"): "删除购物车项",
    ("POST", "/v1/orders/checkout"): "结算下单",
    ("GET", "/v1/orders"): "我的订单列表",
    ("GET", "/v1/orders/{id}"): "订单详情",
    ("POST", "/v1/orders/{id}/cancel"): "取消订单",
    ("POST", "/v1/payments/{orderId}/pay"): "发起支付",
    ("GET", "/v1/payments/{orderId}/status"): "支付状态",
    ("POST", "/v1/payments/webhook"): "支付回调",
    ("GET", "/v1/coupons/available"): "可用优惠券列表",
    ("POST", "/v1/coupons/{code}/check"): "校验优惠券",
    ("GET", "/v1/favorites"): "收藏列表",
    ("POST", "/v1/favorites"): "添加收藏",
    ("DELETE", "/v1/favorites/{favId}"): "按收藏记录移除收藏",
    ("DELETE", "/v1/favorites/product/{productId}"): "按商品移除收藏",
    ("DELETE", "/v1/favorites/bulk"): "批量移除收藏",
    ("GET", "/v1/reviews"): "评价列表",
    ("POST", "/v1/reviews"): "发表评价",
    ("POST", "/v1/aftersales/apply"): "申请售后",
    ("GET", "/v1/aftersales"): "我的售后列表",
    ("POST", "/v1/aftersales/{id}/cancel"): "取消售后",
    ("GET", "/v1/notifications"): "通知列表",
    ("POST", "/v1/notifications"): "创建通知",
    ("POST", "/v1/notifications/{id}/read"): "标记通知已读",
    ("POST", "/v1/notifications/markAllRead"): "全部标记已读",
    ("DELETE", "/v1/notifications"): "清空通知",
    ("POST", "/v1/auth/register"): "注册",
    ("POST", "/v1/auth/login"): "登录",
    ("POST", "/v1/admin/auth/login"): "管理员登录",
    ("GET", "/v1/admin/dashboard/stats"): "管理员仪表盘统计",
    ("GET", "/v1/admin/users"): "管理员用户列表",
    ("GET", "/v1/admin/users/{id}"): "管理员用户详情",
    ("PUT", "/v1/admin/users/{id}/status"): "管理员修改用户状态",
    ("GET", "/v1/admin/products"): "管理员商品列表",
    ("GET", "/v1/admin/products/{id}"): "管理员商品详情",
    ("POST", "/v1/admin/products"): "管理员创建商品",
    ("PUT", "/v1/admin/products/{id}"): "管理员修改商品",
    ("PUT", "/v1/admin/products/{id}/status"): "管理员修改商品状态",
    ("DELETE", "/v1/admin/products/{id}"): "管理员删除商品",
    ("GET", "/v1/admin/categories"): "管理员类目列表",
    ("POST", "/v1/admin/categories"): "管理员创建类目",
    ("PUT", "/v1/admin/categories/{id}"): "管理员修改类目",
    ("DELETE", "/v1/admin/categories/{id}"): "管理员删除类目",
    ("GET", "/v1/admin/orders"): "管理员订单列表",
    ("GET", "/v1/admin/orders/{id}"): "管理员订单详情",
    ("PUT", "/v1/admin/orders/{id}/status"): "管理员修改订单状态",
    ("POST", "/v1/admin/orders/{id}/ship"): "管理员订单发货",
    ("GET", "/v1/admin/aftersales"): "管理员售后列表",
    ("GET", "/v1/admin/aftersales/{id}"): "管理员售后详情",
    ("PUT", "/v1/admin/aftersales/{id}/review"): "管理员售后审核",
    ("GET", "/v1/admin/operation-logs"): "管理员操作日志",
}

ROUTE_DATA = {
    "/v1/auth/register": 'account=TEST_ACCOUNT_SUFFIX（运行时生成唯一账号）；password=TEST_PASSWORD；nickname=测试账号',
    "/v1/auth/login": 'account 从 APP_TEST_ACCOUNT 取；password 从 APP_TEST_PASSWORD 取',
    "/v1/admin/auth/login": 'account 从 ADMIN_TEST_ACCOUNT 取；password 从 ADMIN_TEST_PASSWORD 取',
    "/v1/me/addresses": 'receiver=测试收货人；phone=运行时测试手机号；region=北京市朝阳区；detail=测试路1号；isDefault=0',
    "/v1/products": 'keyword/category/page/size 使用文档字段；创建时 categoryId/product 字段按接口文档提供',
    "/v1/cart/items": 'productId 先从 GET /v1/products?size=1 动态取得；quantity=1',
    "/v1/orders/checkout": 'addressId 先从 GET /v1/me/addresses 取得默认地址；couponCode 使用 GET /v1/coupons/available 返回的券码（可选）',
    "/v1/payments/{orderId}/pay": 'orderId 先从 GET /v1/orders 取得本人待支付订单；channel=alipay',
    "/v1/payments/webhook": 'tradeId 先从支付接口响应取得；status 取文档枚举 SUCCESS/FAILED',
    "/v1/coupons/{code}/check": 'code 从 GET /v1/coupons/available 取得；amount=199.00（示例值）',
    "/v1/favorites": 'productId 先从 GET /v1/products?size=1 动态取得；favId/favIds 从 GET /v1/favorites 动态取得',
    "/v1/reviews": 'orderId/productId 从本人已完成订单及其明细动态取得；rating=5；content=测试评价；images=[]',
    "/v1/aftersales/apply": 'orderId/orderItemId 从本人可申请售后的订单动态取得；qty=1；type=退货；reason=质量问题；evidence=[]',
    "/v1/notifications": 'type=ORDER；title=自动化测试通知；content=测试内容；relatedId 使用关联订单 ID（无关联时为空）',
}


def split_route(case: dict[str, str]) -> tuple[str, str]:
    method, path = case["method_url"].split(" ", 1)
    return method, path.removeprefix("/api")


def clean_body_title(title: str) -> tuple[str, str, str]:
    parts = title.split("_")
    if len(parts) >= 3 and parts[1].isdigit():
        scenario = parts[2]
        expected = "_".join(parts[3:])
    elif len(parts) >= 2:
        scenario = parts[1]
        expected = "_".join(parts[2:])
    else:
        scenario, expected = title, ""
    scenario = re.sub(r"^[0-9]+$", "", scenario)
    expected = re.sub(r"_[0-9]+$", "", expected)
    return title, scenario, expected


BUS_EXPECTED: dict[str, tuple[str, str, str | None]] = {
    "BUS-AUTH-001": ("200", "认证响应：data（用户对象，不含 password）", None),
    "BUS-AUTH-002": ("400", "VALIDATION_FAILED（账号已存在）", None),
    "BUS-AUTH-003": ("400", "VALIDATION_FAILED（密码长度不能少于6位）", None),
    "BUS-AUTH-004": ("200", "认证响应：data.user（后端不限制手机号/邮箱格式）", None),
    "BUS-AUTH-005": ("200", "认证响应：data.token + expiresIn=7200 + data.user", None),
    # The supplied interface document says VALIDATION_FAILED while UserServiceImpl
    # deliberately returns UNAUTHORIZED. Keep this single conflict for review.
    "BUS-AUTH-006": ("400", "UNAUTHORIZED（源码）；VALIDATION_FAILED（接口文档冲突）", "MP-BUS-AUTH-006"),
    "BUS-ME-001": ("200", "200；data（用户对象，不含 password）", None),
    "BUS-ME-002": ("200", "200；新增/修改默认地址后最多一个 isDefault=1", None),
    "BUS-PRD-001": ("200", "200；data 为稳定分页商品数组且不 500", None),
    "BUS-PRD-002": ("404", "404", None),
    # ProductController is explicitly excluded from AuthInterceptor in WebConfig.
    "BUS-PRD-003": ("200", "200；当前源码允许未认证写入（安全缺陷断言）", None),
    "BUS-CART-001": ("200", "200；同商品同规格合并为单条并累计 quantity", None),
    "BUS-CART-002": ("400", "INVALID_QUANTITY", None),
    "BUS-ORD-001": ("200", "200；仅选中项计价并按 SKU/商品库存扣减", None),
    "BUS-ORD-002": ("400", "INSUFFICIENT_STOCK；事务回滚", None),
    "BUS-ORD-003": ("200", "200；仅 status=0 转为 4，重复取消不产生新副作用", None),
    "BUS-ORD-004": ("200", "200；他人订单返回 code=404 且不泄露数据", None),
    "BUS-PAY-001": ("200", "200；tradeId 唯一且初始状态 PENDING", None),
    "BUS-PAY-002": ("200", "200；重复回调不得重复入账；失败即缺陷", None),
    "BUS-CPN-001": ("200", "200；valid/reason/discount 按状态、有效期、门槛顺序返回", None),
    "BUS-FAV-001": ("200", "200；重复添加不产生重复记录，三种删除后列表一致", None),
    "BUS-REV-001": ("400", "ORDER_NOT_FOUND（非本人订单）；rating 越界按需求作缺陷断言", None),
    "BUS-AS-001": ("200", "200；初始 SUBMITTED，仅 SUBMITTED/PROCESSING 可取消", None),
    "BUS-NT-001": ("200", "200；已读、全部已读、清空后的列表状态一致", None),
    "BUS-ADM-001": ("400", "FORBIDDEN", None),
    "BUS-ADM-002": ("200", "200；订单进入发货状态并写入物流字段与 operation-logs", None),
    "BUS-ADM-003": ("200", "200；状态属于允许枚举并保存 adminRemark", None),
}


def expected_for(case: dict[str, str], method: str, path: str) -> tuple[str, str, str | None]:
    """Return deterministic policy/source-backed values for a case."""
    cid, _, _ = clean_body_title(case["title"])
    if case["case_id"].startswith("BUS-"):
        return BUS_EXPECTED[case["case_id"]]
    if "未认证" in cid:
        return "400", "UNAUTHORIZED", None
    if cid.startswith("管理员接口_USER"):
        return "400", "FORBIDDEN", None
    # Spring MVC rejects a missing @PathVariable route with HTTP 404 before the
    # controller is invoked. This is the mandated baseline for all path cases.
    if "缺少必填参数" in cid and "{" in path:
        return "404", "404（非 500 且为 404）", None
    if "缺少必填参数" in cid:
        return "400", "VALIDATION_FAILED", None
    if "分页边界" in cid:
        return "200", "200（不 500、行为一致）", None
    if "重复提交" in cid:
        if method == "DELETE":
            return "400", "资源不存在（拒绝重复删除）", None
        if method == "PUT":
            return "200", "200（重复更新结果一致）", None
        if path == "/v1/payments/webhook":
            return "200", "200（重复回调不得重复入账；失败即缺陷）", None
        # POST uniqueness/command semantics: the endpoint must either return
        # its documented conflict/state code or be safely idempotent.
        if path == "/v1/favorites":
            return "200", "200（重复添加不产生重复记录）", None
        if path in {"/v1/notifications/{id}/read", "/v1/notifications/markAllRead"}:
            return "200", "200（重复标记无新副作用）", None
        if path == "/v1/payments/{orderId}/pay":
            return "200", "200（重复请求结果一致；已支付订单另有 PAYMENT_FAILED 保护）", None
        if path == "/v1/orders/{id}/cancel":
            return "200", "500（重复取消不再产生状态副作用）", None
        if path == "/v1/aftersales/{id}/cancel":
            return "400", "STATE_INVALID（重复取消拒绝）", None
        if path == "/v1/favorites/bulk":
            return "200", "500（重复删除无资源可删）", None
        return "200", "200（重复提交结果一致且不产生未声明副作用）", None
    if "不存在" in cid and path == "/v1/products/{id}":
        return "404", "404", None
    if case["expected_http"].startswith("200"):
        return "200", "200", None
    return case["expected_http"], case["expected_code"], None


def build_case(case: dict[str, str]) -> tuple[dict[str, str], dict[str, str] | None]:
    method, path = split_route(case)
    http, code, manual = expected_for(case, method, path)
    if case["case_id"].startswith("BUS-"):
        # Business titles are supplied product language and must remain intact.
        title = case["title"].replace("（字段待确认）", "")
    else:
        _, scenario, expected_text = clean_body_title(case["title"])
        if "典型请求" in scenario:
            expected_text = f"HTTP {http} + code={code}"
        elif "未认证" in scenario:
            expected_text = "HTTP 400 + error.code=UNAUTHORIZED"
        elif "缺少必填" in scenario or "分页边界" in scenario or "重复提交" in scenario:
            expected_text = f"HTTP {http} + {code}"
        elif manual:
            expected_text = f"人工拍板：{code}"
        elif expected_text in {"返回契约声明状态", "返回参数错误"}:
            expected_text = f"HTTP {http} + code={code}"
        title = f"{NAMES[(method, path)]} [{method} {path}]_{scenario}_{expected_text}"
    auth = "不设置 Authorization" if "未认证" in case["title"] else "使用运行时 token（pytest fixture 登录获取）" if "/v1/" in path and "/auth/" not in path and not path.endswith("/products") and path != "/v1/categories" and path != "/v1/payments/webhook" else ""
    pre = "baseUrl 与账号从 pytest 环境变量取（api_testcases 的 conftest 提供）"
    if auth:
        pre += "；" + auth
    if case["case_id"].startswith("BUS-"):
        original_pre = case["precondition"].replace("111.170.33.2", "baseUrl 环境变量").replace("103.236.91.136", "baseUrl 环境变量")
        prep = []
        if "账号未注册" in original_pre:
            prep.append("生成唯一 TEST_ACCOUNT_SUFFIX 并确认账号不存在")
        if "已有同 account" in original_pre or "已有测试账号" in original_pre:
            prep.append("从 APP_TEST_ACCOUNT/APP_TEST_PASSWORD 登录或由 fixture 预置账号")
        if "已登录" in original_pre or "token" in original_pre.lower():
            prep.append("使用对应运行时账号登录并由 fixture 注入 token")
        if "ADMIN" in original_pre:
            prep.append("使用 ADMIN_TEST_ACCOUNT/ADMIN_TEST_PASSWORD 登录并由 fixture 注入 ADMIN token")
        if "有 SUBMITTED" in original_pre:
            prep.append("登录后通过售后申请流程或 fixture 准备 SUBMITTED 售后记录")
        if "待支付" in original_pre or "已支付" in original_pre or "已完成" in original_pre:
            prep.append("通过购物车→结算→支付/完成流程或隔离 fixture 准备对应订单状态")
        if "存在商品" in original_pre:
            prep.append("先 GET /v1/products?size=1 动态取得商品 ID")
        prep_text = "；".join(prep) if prep else "按接口文档准备所需数据"
        pre = f"baseUrl 与账号从 pytest 环境变量取（api_testcases 的 conftest 提供）；准备步骤：{prep_text}；原条件：{original_pre}"
    data = ROUTE_DATA.get(path, "无请求体；路径参数从对应列表接口动态获取")
    if method == "GET" and path == "/v1/products":
        data = "keyword/category/page/size 按接口文档取值；page=1、size=10"
    if method == "GET" and path in {"/v1/me/addresses", "/v1/cart", "/v1/orders", "/v1/notifications", "/v1/aftersales", "/v1/favorites", "/v1/reviews"}:
        data = "无请求体；page/size 使用接口默认值或文档字段"
    if method == "POST" and path == "/v1/products":
        data = '{"categoryId":"从 GET /v1/categories 动态取得","name":"自动化测试商品","description":"测试数据","tags":"test","price":1.00,"stock":1,"status":1}'
    if "未认证" in case["title"]:
        data = "不发送 Authorization；路径参数按接口前置条件动态获取"
    prep = ""
    if "{id}" in path or "{orderId}" in path or "{favId}" in path or "{productId}" in path or "{code}" in path:
        prep = "先调用对应列表接口动态获取路径参数；"
    method_path = f"{method} {path}"
    steps = f"环境：baseUrl 与账号从 pytest 环境变量取（api_testcases 的 conftest 提供）；{prep}按接口文档构造请求 {method_path}；发送请求；记录请求与响应；按预期断言。"
    if "缺少必填参数" in case["title"] and "{" in path:
        steps += " 缺失路径参数时验证 HTTP 404，且响应不得为 500。"
    if "分页边界" in case["title"]:
        steps += " 使用非法页码/边界 size 重复请求，验证不 500 且归一化或结果行为一致（以后端 controller 的 safePage/safeSize 或 offset 实现为准）。"
    if "重复提交" in case["title"]:
        steps += " 连续发送相同请求并比较第二次响应、资源状态和副作用。"
    if manual:
        steps += f" 执行前依据人工拍板清单 {manual} 确认未定义行为。"
    cleanup = "无（只读请求，不产生业务数据）" if method == "GET" or "未认证" in case["title"] else "使用 fixture 删除本用例创建/修改的测试数据；动态 ID 仅使用测试数据。"
    notes = f"规格依据：接口文档.md 对应接口章节；后端源码 back/src/main/java/com/web/controller/ 与 service/。"
    if manual:
        notes += f" 人工拍板清单：{manual}。"
    expected_detail = code
    if "缺少必填参数" in case["title"] and "{" in path:
        expected_detail = "HTTP 404；响应非 500 且为 404"
    elif "分页边界" in case["title"]:
        expected_detail = "HTTP 200 + code=200；不 500、重复请求行为一致"
    elif "重复提交" in case["title"]:
        expected_detail = code
    revised = {**case, "title": title, "method_url": method_path, "precondition": pre, "payload": data, "steps": f"请求：{method_path}\n请求头：{case['headers']}\n参数：{data}\n步骤：{steps}", "expected_http": http, "expected_code": code, "assertions": f"HTTP 状态码为 {http}；响应业务码/错误码符合 {expected_detail}；响应结构和安全要求符合接口文档及源码。", "cleanup": cleanup, "notes": notes}
    manual_row = None
    if manual:
        manual_row = {"manual_id": manual, "case_id": case["case_id"], "reason": "接口文档与源码未定义可唯一断言的行为；禁止编造。", "current_title": case["title"], "method_url": case["method_url"]}
    return revised, manual_row


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, default=ROOT / "test_cases_inventory.md")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "case-revision-plan.json")
    args = parser.parse_args()
    cases = parse_inventory(args.inventory)
    revisions, manual = [], []
    for case in cases:
        revised, row = build_case(case)
        revisions.append(revised)
        if row:
            manual.append(row)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"cases": revisions, "manual": manual}, ensure_ascii=False, indent=2), encoding="utf-8")
    titles = [case["title"] for case in revisions]
    print(json.dumps({"cases": len(revisions), "manual": len(manual), "title_duplicates": len(titles) - len(set(titles)), "contains_wait": sum("待确认" in c["title"] for c in revisions)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
