#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""尚品汇（Shang Pivot）接口用例生成器。

输入：后端源码端点（`build_api_doc.collect_source_endpoints`）+ springdoc OpenAPI（字段/参数细节）。
输出：14 列用例清单（rule.md v2.6），可直接过 case_lint。

设计要点
- 真值层级：源码 > OpenAPI > 接口文档 > PRD；确定性预期一律回源码确认。
- 成功业务码 = 0（ErrorCode.SUCCESS）；失败族 BizException → HTTP 200 + body 数值码
  （GlobalExceptionHandler 无 @ResponseStatus）；Bean 校验失败 → HTTP 400 + BAD_REQUEST；
  未认证 → HTTP 401 + UNAUTHORIZED；路由未命中 → HTTP 404 + NOT_FOUND（NoResourceFoundException）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_api_doc import auth_requirement, collect_source_endpoints  # noqa: E402

LIST_PAGE = "page=1&size=5"

# ---------------------------------------------------------------- 需求编号映射
def requirement_for(method: str, path: str) -> str:
    """端点 → 需求编号（《需求文档》41 条编号，用于 R28 追溯）。"""
    table = [
        (r"^/admin/auth/", "RBAC-01"),
        (r"^/admin/users", "RBAC-01"),
        (r"^/admin/roles", "RBAC-02"),
        (r"^/admin/depts", "RBAC-03"),
        (r"^/admin/permissions", "RBAC-04"),
        (r"^/admin/dicts", "RBAC-05"),
        (r"^/admin/dict-items", "RBAC-05"),
        (r"^/admin/dashboard", "INF-01"),
        (r"^/admin/operation-logs", "INF-02"),
        (r"^/admin/categories", "PRD-01"),
        (r"^/admin/brands", "PRD-02"),
        (r"^/admin/attributes", "PRD-03"),
        (r"^/admin/products", "PRD-04"),
        (r"^/admin/stocks", "STK-03" if method == "PUT" else "STK-02"),
        (r"^/admin/warehouses", "STK-04"),
        (r"^/admin/in-out", "STK-05"),
        (r"^/admin/orders", "MALL-07"),
        (r"^/admin/after-sales", "MALL-10"),
        (r"^/admin/customers", "CUS-05"),
        (r"^/admin/reviews", "MALL-11"),
        (r"^/admin/site/brand", "CFG-01"),
        (r"^/admin/home/slots", "CFG-02"),
        (r"^/admin/system-configs", "CFG-03"),
        (r"^/admin/files", "CFG-04"),
        (r"^/api/auth/sms", "CUS-01"),
        (r"^/api/auth/login", "CUS-01"),
        (r"^/api/auth/phone", "CUS-01"),
        (r"^/api/auth/refresh", "CUS-02"),
        (r"^/api/auth/logout", "CUS-03"),
        (r"^/api/me$", "CUS-04"),
        (r"^/api/home", "MALL-01"),
        (r"^/api/site/brand", "MALL-01"),
        (r"^/api/products/\{spuId\}/reviews", "MALL-11"),
        (r"^/api/products", "MALL-02"),
        (r"^/api/categories", "MALL-02"),
        (r"^/api/search", "MALL-02"),
        (r"^/api/cart", "MALL-03"),
        (r"^/api/addresses", "MALL-04"),
        (r"^/api/orders/\{id\}/cancel", "MALL-08"),
        (r"^/api/orders/\{id\}/confirm", "MALL-09"),
        (r"^/api/orders", "MALL-05" if method == "POST" else "MALL-07"),
        (r"^/api/payment", "MALL-06"),
        (r"^/api/after-sales", "MALL-10"),
        (r"^/api/reviews", "MALL-11"),
        (r"^/api/favorites", "ME-01"),
        (r"^/api/history", "ME-02"),
        (r"^/api/files", "CFG-04"),
    ]
    for pat, req in table:
        if re.match(pat, path):
            return req
    return "INF-01"

# ------------------------------------------------------- 重复提交（幂等）策略
# 每条策略都必须能指向源码依据（写入用例备注，R35 机器核对）。
# 判定口径：同一请求成功后再发一次会发生什么。
POLICY_OVERRIDES: dict[tuple[str, str], tuple[str, str]] = {
    # POST 创建类（有唯一性检查 → 第二次 CONFLICT）
    ("POST", "/admin/users"): ("conflict", "AdminUserService:64 用户名重复 → CONFLICT"),
    ("POST", "/admin/roles"): ("conflict", "RoleService:52 角色编码重复 → CONFLICT"),
    ("POST", "/admin/permissions"): ("conflict", "PermissionController:51 权限编码重复 → CONFLICT"),
    ("POST", "/admin/dicts"): ("conflict", "DictController.createDict 已补 code 唯一性校验 → CONFLICT"),
    # POST 业务约束类
    ("POST", "/api/after-sales"): ("conflict_aftersale", "AfterSaleService:99 同订单已有进行中售后 → BAD_REQUEST"),
    ("POST", "/api/cart/items"): ("accumulate", "CartService:60-63 同规格累加数量"),
    ("POST", "/api/favorites/{spuId}"): ("no_dup", "FavoriteBrowseService:56-60 已存在则直接 return"),
    ("POST", "/api/reviews"): ("conflict_review", "StorefrontController:286-288 同订单项 → REVIEW_EXISTS"),
    ("POST", "/api/orders"): ("idempotent", "OrderSubmitService:55-59 同 idempotentKey 返回既有订单"),
    ("POST", "/api/orders/{id}/cancel"): ("idempotent_cancel", "OrderSubmitService:141-143 closeAndUnlock 对非 PENDING_PAY 静默 return（幂等取消）"),
    ("POST", "/api/orders/{id}/confirm"): ("state_invalid", "OrderService:111 非待发货 → ORDER_STATUS_INVALID"),
    ("POST", "/admin/orders/{id}/ship"): ("state_invalid", "OrderService:111 非待发货 → ORDER_STATUS_INVALID"),
    ("POST", "/admin/after-sales/{id}/audit"): ("state_invalid", "AfterSaleService:60 非待审核 → AFTER_SALE_STATUS_INVALID"),
    ("POST", "/api/payment/sandbox"): ("state_invalid", "PaymentSandboxService:55 非待支付 → ORDER_STATUS_INVALID"),
    ("POST", "/api/auth/sms/send"): ("cooldown", "CustomerAuthService:44 60 秒冷却 → BAD_REQUEST"),
    ("POST", "/api/cart/clear-invalid"): ("idempotent_nolist", "CartService:119 无可清理项时无副作用"),
    # 无资源列表可回查的接口
    ("POST", "/admin/auth/login"): ("login_repeat", "AuthController:33 重复登录仅再签发令牌"),
    ("POST", "/api/auth/login"): ("login_repeat", "CustomerAuthService:59 重复登录仅再签发令牌"),
    ("POST", "/api/auth/phone"): ("login_repeat", "CustomerAuthService:59 重复登录仅再签发令牌"),
    ("POST", "/admin/auth/refresh"): ("refresh_repeat", "AuthController:39 重复刷新换新 access"),
    ("POST", "/api/auth/refresh"): ("refresh_repeat_mall", "CustomerAuthController:39 重复刷新换新 access，旧令牌不失效"),
    ("POST", "/admin/auth/logout"): ("logout_repeat", "AuthController:46 重复登出仍返回成功"),
    ("POST", "/api/auth/logout"): ("logout_repeat", "CustomerAuthController:46 重复登出仍返回成功"),
    ("POST", "/admin/files/upload"): ("new_object", "FileController:72 每次生成新 objectKey"),
    ("POST", "/api/files/upload"): ("new_object", "CustomerFileController:50 每次生成新 objectKey"),
    ("POST", "/admin/files/presign"): ("new_object", "FileController:107 每次生成新 objectKey"),
    ("POST", "/api/files/presign"): ("new_object", "CustomerFileController:75 每次生成新 objectKey"),
    ("POST", "/admin/files/confirm"): ("new_record", "FileController:125 每次插入一条 file_object"),

    # PUT 覆盖式更新中唯一的例外：库存按增量调整
    ("PUT", "/admin/stocks/adjust"): ("accumulate_stock", "StockService:106 physical + changeQty 增量生效"),
}
# 未列出的端点按方法族给出默认策略（同样附带依据，R35 要求备注可查）
POLICY_FAMILY_DEFAULT: dict[str, tuple[str, str]] = {
    "POST": ("new_record", "源码无重复性校验，直接 insert（每个端点见 接口文档 实现位置）"),
    "PUT": ("idempotent", "updateById 覆盖式写入，重复提交结果一致"),
    "PATCH": ("idempotent", "updateById 覆盖式写入，重复提交结果一致"),
}
# DELETE 需区分「有无存在性检查」——已逐条实测（不存在的 id 再删一次的返回码）
DELETE_POLICY: dict[str, tuple[str, str]] = {
    "/admin/attributes/{id}": ("idempotent_delete", "AttributeController:66 无存在性检查，重复删除仍 200"),
    "/admin/brands/{id}": ("idempotent_delete", "BrandController:83 无存在性检查，重复删除仍 200"),
    "/admin/categories/{id}": ("idempotent_delete", "CategoryController:99 无存在性检查，重复删除仍 200"),
    "/admin/depts/{id}": ("idempotent_delete", "DeptController:99 无存在性检查，重复删除仍 200"),
    "/admin/dict-items/{id}": ("idempotent_delete", "DictController:136 无存在性检查，重复删除仍 200"),
    "/admin/dicts/{id}": ("idempotent_delete", "DictController:74 无存在性检查，重复删除仍 200"),
    "/admin/permissions/{id}": ("idempotent_delete", "PermissionController:104 无存在性检查，重复删除仍 200"),
    "/admin/roles/{id}": ("idempotent_delete", "RoleService:82 无存在性检查，重复删除仍 200"),
    "/api/favorites/{spuId}": ("idempotent_delete", "FavoriteBrowseService:71 按条件删除，无存在性检查"),
    "/api/history": ("idempotent_delete", "FavoriteBrowseService:129 清空式删除，无存在性检查"),
    "/admin/products/{id}": ("notfound", "ProductService:240 requireSpu → NOT_FOUND"),
    "/admin/users/{id}": ("notfound", "AdminUserService:116 require → NOT_FOUND"),
    "/api/cart/items/{id}": ("notfound", "CartService:145-148 requireOwnedItem → NOT_FOUND"),
    "/api/addresses/{id}": ("notfound", "AddressService:67 设计应 NOT_FOUND；源码现状不存在 id 触发 NPE→500（缺陷 D-3）"),
}
REPEAT_TEXT = {
    "conflict": ("重复提交_提示已存在", "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查列表校验本次唯一键仅一条记录"),
    "conflict_review": ("重复提交_提示评价已存在", "200", "REVIEW_EXISTS",
                        "HTTP=200；code=REVIEW_EXISTS；回查 GET /api/products/{spuId}/reviews 校验同一订单项仍只有一条评价"),
    "conflict_aftersale": ("重复提交_提示已有进行中售后", "200", "BAD_REQUEST",
                           "HTTP=200；code=BAD_REQUEST；回查 GET /api/after-sales 校验该订单的售后单仍只有一条"),
    "notfound": ("重复提交_提示资源不存在", "200", "NOT_FOUND",
                 "HTTP=200；code=NOT_FOUND；回查列表确认该记录已不存在"),
    "idempotent": ("重复提交_结果一致", "200", "0", ""),
    "idempotent_delete": ("重复删除_第二次不报错", "200", "0", ""),
    "idempotent_nolist": ("重复提交_无额外副作用", "200", "0", ""),
    "accumulate": ("重复添加_数量累加", "200", "0",
                   "HTTP=200；code=0；回查 GET /api/cart 校验 data.items 中该规格数量等于两次提交数量之和"),
    "accumulate_stock": ("重复提交_库存按增量再次变动", "200", "0",
                         "HTTP=200；code=0；回查 GET /admin/stocks 校验该 SKU 物理库存等于初始值加两倍 changeQty（增量两次生效）"),
    "no_dup": ("重复添加_不产生重复记录", "200", "0",
               "HTTP=200；code=0；回查 GET /api/favorites 校验列表中该商品仅出现一次"),
    "idempotent_cancel": ("重复提交_幂等关闭且库存只解锁一次", "200", "0",
                          "HTTP=200；code=0；回查 GET /api/orders/{id} 校验 data.status 为 CLOSED；"
                          "回查 GET /admin/stocks 校验该 SKU 锁定库存等于首次取消后的值（只解锁一次）"),
    "state_invalid": ("重复提交_不再产生副作用", "200", "ORDER_STATUS_INVALID",
                      "HTTP=200；code=ORDER_STATUS_INVALID；回查详情校验状态未再次变更"),
    "cooldown": ("重复提交_提示发送过于频繁", "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；响应 message 包含「发送过于频繁」"),
    "new_record": ("重复提交_生成独立业务记录", "200", "0",
                   "HTTP=200；code=0；回查列表校验 data.total 比首次提交后增加 1"),
    "new_object": ("重复提交_生成新对象键", "200", "0",
                   "HTTP=200；code=0；data.objectKey 与首次上传返回的对象键不同"),
    "login_repeat": ("重复提交_再次签发令牌", "200", "0",
                     "HTTP=200；code=0；data.accessToken 非空"),
    "refresh_repeat": ("重复提交_刷新令牌已轮换被拒绝", "200", "TOKEN_INVALID",
                       "HTTP=200；error.code=TOKEN_INVALID（实测：管理端刷新即轮换令牌，旧 refreshToken 失效）"),
    "refresh_repeat_mall": ("重复提交_再次刷新令牌", "200", "0",
                            "HTTP=200；code=0；data.accessToken 非空（实测：C 端刷新不轮换旧令牌）"),
    "logout_repeat": ("重复提交_令牌已失效被拒绝", "401", "TOKEN_INVALID",
                      "HTTP=401；error.code=TOKEN_INVALID（源码登出即把令牌加入黑名单，第二次必然被拒）"),
}


def repeat_policy(method: str, path: str) -> tuple[str, str]:
    """返回 (策略, 源码依据)。"""
    if (method, path) in POLICY_OVERRIDES:
        return POLICY_OVERRIDES[(method, path)]
    if method == "DELETE":
        return DELETE_POLICY.get(path, ("notfound", "源码 require → NOT_FOUND"))
    return POLICY_FAMILY_DEFAULT.get(method, ("idempotent", "updateById 覆盖式写入"))


VERB_RESULT = {
    ("GET", "list"): "查询成功",
    ("GET", "detail"): "查询成功",
    ("GET", "other"): "查询成功",
    ("POST", "create"): "创建成功",
    ("POST", "action"): "操作成功",
    ("POST", "login"): "登录成功",
    ("POST", "send"): "发送成功",
    ("PUT", "update"): "修改成功",
    ("DELETE", "delete"): "删除成功",
}


def classify(method: str, path: str, has_body: bool) -> str:
    if method == "GET":
        return "other" if path.endswith("/export") else ("detail" if "{" in path else "list")
    if method == "DELETE":
        return "delete"
    if method in ("PUT", "PATCH"):
        return "update"
    if path.endswith("/logout"):
        return "action"
    if path.endswith("/login") or path.endswith("/phone"):
        return "login"
    if path.endswith("/sms/send"):
        return "send"
    if re.search(r"/\{[^}]+\}/(on|off|ship|audit|confirm|cancel)$", path) or path.endswith("/sandbox"):
        return "action"
    return "create" if has_body else "action"


# 源码未写 @Operation summary 的端点（C 端 StorefrontController / CustomerFileController）业务名
FALLBACK_NAME = {
    ("GET", "/api/home"): "首页信息",
    ("GET", "/api/site/brand"): "站点品牌",
    ("GET", "/api/categories"): "分类列表",
    ("GET", "/api/products"): "商品列表",
    ("GET", "/api/products/{spuId}"): "商品详情",
    ("GET", "/api/products/{spuId}/reviews"): "商品评价列表",
    ("GET", "/api/search"): "关键词搜索",
    ("GET", "/api/cart"): "购物车列表",
    ("POST", "/api/cart/items"): "加入购物车",
    ("PUT", "/api/cart/items/{id}"): "修改购物车数量",
    ("DELETE", "/api/cart/items/{id}"): "移除购物车项",
    ("POST", "/api/cart/clear-invalid"): "清理购物车失效项",
    ("GET", "/api/addresses"): "收货地址列表",
    ("POST", "/api/addresses"): "新增收货地址",
    ("PUT", "/api/addresses/{id}"): "修改收货地址",
    ("DELETE", "/api/addresses/{id}"): "删除收货地址",
    ("PUT", "/api/addresses/{id}/default"): "设置默认收货地址",
    ("GET", "/api/orders"): "我的订单列表",
    ("POST", "/api/orders"): "提交订单",
    ("GET", "/api/orders/{id}"): "订单详情",
    ("GET", "/api/orders/{id}/logistics"): "物流查询",
    ("POST", "/api/orders/{id}/cancel"): "取消订单",
    ("POST", "/api/orders/{id}/confirm"): "确认收货",
    ("POST", "/api/payment/sandbox"): "沙箱支付",
    ("GET", "/api/payment/{orderId}/status"): "支付状态查询",
    ("GET", "/api/after-sales"): "我的售后列表",
    ("POST", "/api/after-sales"): "申请售后",
    ("GET", "/api/after-sales/{id}"): "售后详情",
    ("GET", "/api/favorites"): "收藏列表",
    ("POST", "/api/favorites/{spuId}"): "收藏商品",
    ("DELETE", "/api/favorites/{spuId}"): "取消收藏",
    ("GET", "/api/history"): "浏览足迹",
    ("DELETE", "/api/history"): "清空浏览足迹",
    ("POST", "/api/files/presign"): "文件预签名上传",
    ("POST", "/api/files/confirm"): "文件上传确认",
}


def fallback_name(method: str, path: str) -> str:
    return FALLBACK_NAME.get((method, path), "接口")


def module_name(summary: str, path: str) -> str:
    if not summary:
        summary = fallback_name(e["method"], path)
    mod = (summary or "接口").strip()
    mod = re.sub(r"^(管理员|后台|C\s*端)", "", mod)
    if path.startswith("/admin/"):
        mod = "管理员" + mod
    return mod


def row(cells: list[str]) -> str:
    return "| " + " | ".join(str(c).replace("\n", "<br>").replace("|", "\\|") for c in cells) + " |"


def steps_of(method_url: str, headers: str, payload: str, tail: str) -> str:
    return (f"请求：{method_url}<br>请求头：{headers}<br>参数：{payload}"
            f"<br>步骤：{tail}")


# 服务层消费 Map 请求体、Controller 无 body.get 的端点（源码 SiteBrandService.update）
BODY_KEYS_OVERRIDE = {
    ("PUT", "/admin/site/brand"): ["name", "slogan", "logoKey", "faviconKey"],
    ("POST", "/api/orders"): ["addressId", "idempotentKey", "cartItemIds"],
    ("POST", "/api/after-sales"): ["orderId", "type", "reason", "refundAmount"],
    ("POST", "/api/reviews"): ["orderItemId", "rating", "content", "picKeys"],
    ("POST", "/api/payment/sandbox"): ["orderId"],
    ("PUT", "/api/me"): ["nickname", "avatar"],
}
# 请求体是数组/标量的端点：直接给出可执行且只作用于专属数据的字面量模板
# 认证类端点：凭据从环境变量取，避免生成器编造账号（否则必然 40101）
AUTH_BODY_OVERRIDE = {
    ("POST", "/api/files/confirm"): '{"objectKey": "先调用 POST /api/files/presign 取 objectKey", '
                                    '"bizType": "AVATAR", "contentType": "image/png", "sizeBytes": 1024}', 
    ("POST", "/admin/auth/login"): '{"username": "从环境变量 ADMIN_TEST_ACCOUNT 取", '
                                   '"password": "从环境变量 ADMIN_TEST_PASSWORD 取"}',
    ("POST", "/api/auth/login"): '{"phone": "从环境变量 APP_TEST_ACCOUNT 取", '
                                 '"code": "从环境变量 APP_TEST_SMS_CODE 取", '
                                 '"clientType": "web"}',
    ("POST", "/api/auth/phone"): '{"phone": "从环境变量 APP_TEST_ACCOUNT 取", '
                                 '"code": "从环境变量 APP_TEST_SMS_CODE 取", '
                                 '"clientType": "web"}',
    ("POST", "/api/auth/sms/send"): '{"phone": "从环境变量 APP_TEST_ACCOUNT 取"}',
    ("POST", "/api/files/presign"): '{"bizType": "AVATAR", "contentType": "image/png", "size": 1024, '
                                    '"filename": "autotest.png"}',
    ("POST", "/admin/auth/refresh"): '{"refreshToken": "从登录响应 data.refreshToken 取"}',
    ("POST", "/api/auth/refresh"): '{"refreshToken": "从登录响应 data.refreshToken 取"}',
}
# 状态字段的端点相关取值（商品造数须为草稿，否则无法删除）
STATUS_BY_PATH = {"/admin/products": "DRAFT", "/admin/dicts": "ENABLED"}

BODY_LITERAL_OVERRIDE = {
    ("POST", "/admin/files/upload"): "multipart/form-data：file=专属测试图片（image/png），bizType=PRODUCT",
    ("POST", "/api/files/upload"): "multipart/form-data：file=专属测试图片（image/png），bizType=AVATAR",
    ("POST", "/admin/products"):
        '{"name": "自动化测试商品", "categoryId": "先调用 GET /admin/categories 取一条记录的 id", '
        '"brandId": "先调用 GET /admin/brands 取一条记录的 id", "status": "DRAFT", "pics": ["先调用文件上传接口取 objectKey"], '
        '"skus": [{"title": "自动化测试规格", "price": 9.90, "stock": 5, "specs": {"规格": "标准"}}]}',
    ("PUT", "/admin/products/batch-off"):
        '[本次创建的专属商品 id（先调用 GET /admin/products?page=1&size=20 取本用例刚创建的草稿商品 id）]',
}


def _value_hint(key: str, path: str = "") -> str:
    """Map 型请求体的字段取值约定（源码 body.get("x") 派生的字段名 → 可执行取值）。"""
    low = key.lower()
    if low == "quantity" or low == "qty":
        return "1"
    if low == "rating":
        return "5"
    if low == "status":
        return '"ENABLED"'
    if low == "password":
        return '"<从环境变量读取>"'
    if low == "nickname":
        return '"自动化测试昵称"'
    if low == "avatar":
        return '"对象键（先调用文件上传接口取 objectKey）"'
    if low == "content":
        return '"自动化测试内容"'
    if low == "reason":
        return '"自动化测试原因"'
    if low == "type":
        return '"REFUND"'
    if low == "phone":
        return '"13800138000"'
    if low == "clienttype":
        return '"web"'
    if low.endswith("id") or low.endswith("ids"):
        lst = ID_LIST.get(low) or list_hint or "对应资源列表接口"
        # C 端端点必须指向 C 端的资源列表（管理端列表取不到本人口令的订单/地址）
        if path.startswith("/api/") and low in ("orderid", "orderitemid"):
            lst = "/api/orders"
        if path.startswith("/api/") and low in ("skuid", "spuid"):
            lst = "/api/products"
        return f'"先调用 GET {lst} 取一条记录的 id"' 
    return json.dumps(_string_hint(key), ensure_ascii=False)


def schema_to_json(schema: dict, comps: dict, depth: int = 0) -> str:
    """把 OpenAPI schema 渲染成一份可直接提交的示例 JSON。"""
    if not schema:
        return ""
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        return schema_to_json(comps.get(name, {}), comps, depth)
    t = schema.get("type")
    if t == "object" or "properties" in schema:
        props = schema.get("properties") or {}
        required = set(schema.get("required") or [])
        audit = {"id", "createdAt", "updatedAt", "deleted", "traceId"}
        keep = {k: v for k, v in props.items() if k in required and k not in audit}
        if not keep:
            biz = [k for k in ("status", "type", "quantity", "name", "code", "price", "title")
                   if k in props]
            keep = {k: props[k] for k in biz[:4]}
        if not keep:
            keep = dict(list({k: v for k, v in props.items() if k not in audit}.items())[:4])
        if depth >= 1:
            return "{…}"
        inner = ", ".join(f'"{k}": {schema_to_json(v, comps, depth + 1)}' for k, v in keep.items())
        return "{" + inner + "}"
    if t == "array":
        return "[" + schema_to_json(schema.get("items", {}), comps, depth + 1) + "]"
    if schema.get("example") is not None:
        return json.dumps(schema["example"], ensure_ascii=False)
    return {"string": '"字符串"', "integer": "1", "number": "1", "boolean": "true"}.get(t, '"字符串"')


def positive_anchor(method: str, path: str, resp_schema: dict, comps: dict, detail_path: str,
                    is_paged: bool = False) -> str:
    """正向断言的数据层锚点（rule.md §3.6）。

    源码大量返回未类型化 Map，字段级断言无从静态推导；此处给出**可执行且为真**的锚点，
    深度限制在备注中标注（不编造字段名，避免制造假缺陷）。
    """
    if method == "DELETE":
        return f"回查 GET {detail_path} 校验 error.code=NOT_FOUND"
    if method == "GET":
        if is_paged:
            return "data.total 非空且 data.records 为数组"
        if "{" in path:
            return f"回查 GET {path} 校验 data 为对象且非空"
        return "data 为对象或数组且非空"
    s = resp_schema or {}
    if "$ref" in s:
        name = s["$ref"].rsplit("/", 1)[-1]
        if name == "ResultVoid":
            return f"回查 GET {detail_path} 校验本次写入已生效"
        s = comps.get(name, {})
    props = (s.get("properties") or {}).get("data") or {}
    if "$ref" in props:
        props = comps.get(props["$ref"].rsplit("/", 1)[-1], {})
    if props.get("type") == "integer":
        return "data 为新建记录 id 且大于 0"
    if props.get("type") == "array":
        return "data 为数组且元素包含 id 字段"
    inner = props.get("properties") or {}
    if inner:
        for key in ("id", "total", "records", "status", "count", "list"):
            if key in inner:
                if key == "records":
                    return "data.records 为数组且 data.total 为数字"
                return f"data.{key} 非空"
        return f"data.{next(iter(inner))} 非空"
    if props:
        return f"回查 GET {detail_path} 校验响应 data 包含业务字段且非空"
    return f"回查 GET {detail_path} 校验本次写入已生效"


AUDIT_FIELDS = {"id", "createdAt", "updatedAt", "deleted", "traceId", "version", "page", "size"}
EXTRA_BIZ = ("name", "code", "title", "label", "value", "price", "content", "remark",
             "slogan", "detailHtml", "pics", "options", "key", "categoryId", "brandId",
             "parentId", "skuId", "spuId", "warehouseId", "orderId", "type", "status")
TARGET_STATE = {"on": "ON", "off": "OFF", "ship": "PENDING_RECEIVE",
                "confirm": "COMPLETED", "cancel": "CLOSED", "audit": "COMPLETED"}
# 无资源可回查的特殊族：直接断言响应字段
ANCHOR_OVERRIDE = {
    "/admin/auth/login": "data.accessToken 非空且 data.user.username 等于登录账号",
    "/api/auth/login": "data.accessToken 非空且 data.customer.phone 与登录手机号一致",
    "/api/auth/phone": "data.accessToken 非空且 data.customer.phone 与登录手机号一致",
    "/admin/auth/refresh": "data.accessToken 非空",
    "/api/auth/refresh": "data.accessToken 非空",
    "/admin/auth/logout": "回查 GET /admin/auth/me 校验 error.code=UNAUTHORIZED（令牌已失效）",
    "/api/auth/logout": "回查 GET /api/me 校验 error.code=UNAUTHORIZED（令牌已失效）",
    "/api/auth/sms/send": "data.cooldownSec 为数字且大于 0",
    "/admin/files/upload": "data.objectKey 非空且 data.url 非空",
    "/api/files/upload": "data.objectKey 非空且 data.url 非空",
    "/admin/files/presign": "data.objectKey 非空且 data.uploadUrl 非空",
    "/api/files/presign": "data.objectKey 非空且 data.uploadUrl 非空",
    "/admin/files/confirm": "data.objectKey 等于本次提交的对象键",
    "/api/files/confirm": "data.objectKey 等于本次提交的对象键",
    "/api/payment/sandbox": "回查 GET /api/payment/{orderId}/status 校验 data.status 为 PAID",
    "/api/reviews": "回查 GET /api/products/{spuId}/reviews 校验本次评价出现在 data 列表中",
    "/admin/stocks/adjust": "回查 GET /admin/stocks?page=1&size=20 校验该 SKU 物理库存等于初始值加本次 changeQty",
    "/api/addresses/{id}/default": "回查 GET /api/addresses?page=1&size=20 校验目标记录 isDefault 为 true",
    "/admin/files/view": "响应 Content-Type 为图片类型且响应体非空",
    "/api/files/view": "响应 Content-Type 为图片类型且响应体非空",
}


ID_LIST = {
    "categoryid": "/admin/categories", "brandid": "/admin/brands", "parentid": "/admin/categories/tree",
    "skuid": "/admin/products", "spuid": "/admin/products", "warehouseid": "/admin/warehouses",
    "orderid": "/admin/orders", "roleids": "/admin/roles/all", "addressid": "/api/addresses",
    "cartitemids": "/api/cart", "orderitemid": "/api/orders", "slotid": "/admin/home/slots",
    "userid": "/admin/users", "dictid": "/admin/dicts", "permissionids": "/admin/permissions",
    "evidenceref": "",
}


NUMERIC_KEY = re.compile(r"(size|bytes|qty|quantity|count|amount|price|sort|rating|level|stock|num|threshold)$", re.I)
STRING_HINT = {
    "code": "AUTOTEST", "filename": "autotest.png",
    "name": "自动化测试名称", "code": "AUTOTEST", "title": "自动化测试标题",
    "label": "自动化测试标签", "value": "AUTOTEST", "remark": "自动化测试备注",
    "reason": "自动化测试原因", "content": "自动化测试内容", "subtitle": "自动化测试副标题",
    "receiver": "测试收货人", "province": "北京市", "city": "北京市", "district": "朝阳区",
    "detail": "建国路 88 号", "region": "北京市朝阳区", "slogan": "选好物，享生活",
    "nickname": "自动化测试昵称", "username": "autotest01", "password": "从环境变量读取",
    "trackingNo": "SF1234567890", "carrier": "顺丰速运", "billNo": "AUTO20261006001",
    "keyword": "测试", "contentType": "image/png", "bizType": "PRODUCT",
    "objectKey": "先调用 POST /admin/files/presign 获取 objectKey",
    "logo": "先调用文件上传接口取 objectKey", "logoKey": "先调用文件上传接口取 objectKey",
    "faviconKey": "先调用文件上传接口取 objectKey", "avatar": "先调用文件上传接口取 objectKey",
    "imageKey": "先调用文件上传接口取 objectKey", "configValue": "199",
}


def _string_hint(key: str) -> str:
    return STRING_HINT.get(key, "自动化测试")


def list_hint_for(path: str, gets: set[str]) -> str:
    """按端点路径上溯一个真实存在的 GET 列表，作为字段取值约定的兜底指向。"""
    segs = [s for s in path.strip("/").split("/") if s]
    for i in range(len(segs), 0, -1):
        cand = "/" + "/".join(segs[:i])
        cand = re.sub(r"/\{[^}]+\}.*$", "", cand)
        if cand in gets:
            return cand
    return ""


def _value_by_type(key: str, typ: str, path: str = "", list_hint: str = "") -> str:
    base = re.sub(r"<.*>", "", typ).strip().split(".")[-1]
    if base == "String" and NUMERIC_KEY.search(key):
        return "1"
    if key == "code" and "/auth/" in path:
        return '"123456"'
    if base in ("Long", "Integer", "int", "long") or key.lower().endswith(("id", "ids")):
        if key.lower().endswith("id") or key.lower().endswith("ids"):
            low = key.lower()
            lst = ID_LIST.get(low) or list_hint or "对应资源列表"
            if path.startswith("/api/") and low in ("orderid", "orderitemid"):
                lst = "/api/orders"          # C 端订单须取本人口令的订单
            if path.startswith("/api/") and low in ("skuid", "spuid"):
                lst = "/api/products"        # C 端取在售商品（执行器再两跳到 SKU）
            return f'"先调用 GET {lst} 取一条记录的 {key}"'
        return "1"
    if base in ("BigDecimal", "Double", "Float", "double"):
        return "9.90"
    if key == "status" and path in STATUS_BY_PATH:
        return f'"{STATUS_BY_PATH[path]}"'
    if base in ("String",) and key == "type":
        # type 的取值随业务域不同：售后 REFUND / 出入库 PURCHASE_IN / 属性 SPEC
        if "/after-sales" in path:
            return '"REFUND"'
        if "/in-out" in path:
            return '"PURCHASE_IN"'
        if "/attributes" in path:
            return '"SPEC"'
        if "/cart" in path or "/addresses" in path:
            return '"默认"'
        return '"REFUND"' 
    if base == "Boolean" or base == "boolean":
        return "true"
    if base in ("OffsetDateTime", "LocalDateTime", "Date"):
        return '"2026-12-31T23:59:59+08:00"'
    if base.startswith("List") or base.startswith("Set"):
        inner = typ[typ.find("<") + 1: typ.rfind(">")] if "<" in typ else "String"
        inner_s = re.sub(r"<.*>", "", inner).strip().split(".")[-1]
        if inner_s in ("Long", "Integer", "int", "long"):
            return f'["先调用 GET {list_hint or "对应列表接口"} 取一条记录的 id"]'
        if inner_s == "String":
            return '["自动化测试"]'
        if inner_s == "ItemReq":   # 出入库明细
            return ('[{"skuId": "先调用 GET /admin/products 取一条记录的 id", "qty": 2}]')
        return f'[{{"id": "先调用 GET {list_hint or "对应列表接口"} 取一条记录的 id"}}]' 
    if base in ("Map", "Object") or base.startswith("Map<"):
        return "{}"
    return _value_hint(key, path)


def build_body(meta: dict, comps: dict, schema: dict, path: str = "",
               list_hint: str = "") -> tuple[str, list[str]]:
    """请求体：源码 DTO 必填 + 源码消费字段 + 实体业务字段，回落到 OpenAPI schema。

    返回 (JSON 文本, 参与渲染的字段名列表)。
    """
    types = {}
    for f in meta.get("dto_fields") or []:
        if ":" in f:
            n, ty = f.split(":", 1)
            types[n] = ty
    keys: list[str] = []
    for k in list(meta.get("dto_required") or []) + list(meta.get("biz_fields") or []):
        if k not in keys:
            keys.append(k)
    # DTO 自身字段全量纳入（去审计字段），保证请求体可直接执行
    for f in meta.get("dto_fields") or []:
        n = f.split(":", 1)[0]
        if n not in keys and n not in AUDIT_FIELDS:
            keys.append(n)
    keys = [k for k in keys if k not in AUDIT_FIELDS][:10]
    if keys:
        parts = []
        for k in keys:
            v = _value_by_type(k, types.get(k, "String"), path, list_hint)
            if v in ("[]", "{}"):   # 空数组/空对象不作为典型请求值（源码对空数组会 500，属缺陷）
                continue
            if v in ("true", "false") or re.match(r"^-?\d+(\.\d+)?$", v) or v.startswith(("[", "{")):
                parts.append(f'"{k}": {v}')
            else:
                parts.append(f'"{k}": {v}' if v.startswith('"') else f'"{k}": "{v}"')
        return "{" + ", ".join(parts) + "}", keys
    js = schema_to_json(schema, comps)
    if js and js not in ("{}", "{…}"):
        return js, []
    return "", []


def resolve_verify(method: str, path: str, gets: set[str]) -> tuple[str, str]:
    """解析真实存在的回查目标：优先资源详情，其次资源列表。返回 (kind, target)。"""
    m = re.match(r"^(.*)/\{[^}]+\}/(on|off|ship|audit|confirm|cancel|status|default)$", path)
    if m:
        detail = m.group(1) + "/{id}"
        if detail in gets:
            return "detail", detail
        if m.group(1) in gets:
            return "list", m.group(1)
    if method == "GET" and "{" in path and path in gets:
        return "self_detail", path
    # 创建类优先用列表回查（新建记录 id 在断言期未知）
    if method == "POST" and path in gets:
        return "list", path
    if "{" not in path and (path + "/{id}") in gets:
        return "detail", path + "/{id}"
    # 祖先回退：逐级上溯找真实存在的 GET（列表）
    segs = [s for s in path.strip("/").split("/") if s]
    for i in range(len(segs), 0, -1):
        cand = "/" + "/".join(segs[:i])
        if cand in gets:
            return ("detail" if "{" in cand else "list"), cand
    return "none", ""


def anchor_text(method: str, path: str, kind_verify: str, target: str, key_field: str,
                status_family: bool = False, paged: bool = False) -> str:
    page_q = "?page=1&size=20" if paged else ""
    """status_family: 请求体携带状态字段（status / on-off / batch-off）——断言具体状态值。"""
    if status_family:
        seg = path.rstrip("/").split("/")[-1]
        state = TARGET_STATE.get(seg)
        if kind_verify == "detail":
            return (f"回查 GET {target} 校验 data.status 为 {state}" if state
                    else f"回查 GET {target} 校验 data.status 等于本次提交的状态值")
        if kind_verify == "list":
            return (f"回查 GET {target}{page_q} 校验目标记录 status 为 {state}" if state
                    else f"回查 GET {target}{page_q} 校验目标记录 status 等于本次提交的状态值")
    if kind_verify == "detail":
        if method == "DELETE":
            return f"回查 GET {target} 校验 error.code=NOT_FOUND"
        seg = path.rstrip("/").split("/")[-1]
        state = TARGET_STATE.get(seg)
        if state:
            return f"回查 GET {target} 校验 data.status 为 {state}"
        return f"回查 GET {target} 校验关键字段与本次提交值一致"
    if kind_verify == "list":
        if method == "DELETE":
            return f"回查 GET {target}{page_q} 校验该记录已不在 data.records 中"
        if method == "POST":
            return f"回查 GET {target}{page_q} 校验 data.total 比提交前增加 1"
        return f"回查 GET {target}{page_q} 校验目标记录的{key_field}等于本次提交值"
    if kind_verify == "self_detail":
        return "data.id 等于请求路径参数 id"
    return ""


def L_norm(p: str) -> str:
    return re.sub(r"\{[^}]+\}", "{id}", p)


def cleanup_for(method: str, path: str, kind: str, dels: set[str]) -> str:
    """清理指令必须可映射到真实端点，或显式声明不可还原。"""
    if method == "GET":
        return "无需清理（只读）"
    root = re.sub(r"/\{[^}]+\}.*$", "", path)
    delete_target = root + "/{id}"
    if method == "DELETE":
        return "无需清理（删除自身即为清理；使用专属测试数据）"
    if path in ANCHOR_OVERRIDE and "/auth/" in path:
        return "无需清理（登录/刷新类接口只签发令牌，不产生业务实体数据）"
    if "/files/" in path:
        return "无需清理（MinIO 对象无删除接口，用例只上传专属测试文件，不覆盖环境既有对象）"
    if path in ("/api/orders", "/api/payment/sandbox"):
        return "用例结束后调用 POST /api/orders/{id}/cancel 关闭本次下单产生的专属订单并释放库存"
    if kind == "action":
        if path.endswith("/cancel"):
            return "无需清理（取消动作不可撤销，全程使用专属测试数据，不触碰环境既有资产）"
        return "无需清理（状态类动作不可撤销，全程使用专属测试数据，不触碰环境既有资产）"
    if path == "/admin/stocks/adjust":
        return "用例结束后以等量反向调整（changeQty 取负）还原库存至操作前的水平"
    if method == "PUT":
        if delete_target in dels:
            return f"用例结束后调用 DELETE {delete_target} 移除本次更新所使用的专属测试数据"
        return "用例结束后调用同一接口还原为操作前的值（前置条件已记录原值）"
    if method in ("POST", "PATCH"):
        if delete_target in dels:
            return f"用例结束后调用 DELETE {delete_target} 移除本次创建的专属测试数据"
        if "/cart" in path:
            return "用例结束后调用 DELETE /api/cart/items/{id} 移除本次加入的购物车行"
        return "无需清理（该资源无删除接口，全程使用专属测试数据，不触碰环境既有资产）"
    return "无需清理（只读）"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--openapi", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--model", type=Path,
                    default=Path(__file__).resolve().parents[1] / "artifacts" / "endpoint-model.json")
    args = ap.parse_args()

    model = {}
    if args.model.exists():
        model = {(e["method"], e["path"]): e for e in
                 json.loads(args.model.read_text(encoding="utf-8"))["endpoints"]}
    src = collect_source_endpoints(args.repo_root)
    doc = json.loads(args.openapi.read_text(encoding="utf-8"))
    comps = doc.get("components", {}).get("schemas", {})
    ops = {(m.upper(), p): o for p, item in doc["paths"].items()
           for m, o in item.items() if m.upper() in ("GET", "POST", "PUT", "DELETE", "PATCH")}

    gets = {L_norm(p) for (m, p) in ops if m == "GET"}
    paged_paths = {L_norm(p) for (m, p), o in ops.items()
                   if any(x.get("name") in ("page", "size") for x in (o.get("parameters") or []))}
    dels = {L_norm(p) for (m, p) in ops if m == "DELETE"}
    rows: list[str] = []
    stats = {"正向": 0, "鉴权": 0, "参数": 0, "幂等": 0, "边界": 0}
    used_titles: set[str] = set()

    # 模块名去重预扫描：同一 summary 命中多个端点时，追加路径末段以便区分
    summary_counts: dict[str, int] = {}
    for e in src:
        op0 = ops.get((e["method"], e["path"]), {})
        s0 = e["summary"] or op0.get("summary") or fallback_name(e["method"], e["path"])
        summary_counts[s0] = summary_counts.get(s0, 0) + 1

    def unique_module(e: dict, op: dict) -> str:
        s0 = e["summary"] or op.get("summary") or fallback_name(e["method"], e["path"])
        mod0 = module_name(s0, e["path"])
        if summary_counts[s0] > 1:
            seg = [x for x in e["path"].split("/") if x and not x.startswith("{")]
            mod0 = f"{mod0}（{seg[-1] if seg else e['method'].lower()}）"
        return mod0

    def emit(case_id, pri, kind, title, pre, method, path, headers, payload,
             tail, http, code, assertions, cleanup, notes):
        assert title not in used_titles, f"标题重复：{title}"
        used_titles.add(title)
        mu = f"{method} {path}"
        rows.append(row([case_id, pri, kind, title, pre, mu, headers, payload,
                         steps_of(mu, headers, payload, tail), http, code,
                         assertions, cleanup, notes]))

    for e in sorted(src, key=lambda x: (x["path"], x["method"])):
        method, path = e["method"], e["path"]
        op = ops.get((method, path), {})
        summary = e["summary"] or op.get("summary") or f"{method} {path}"
        mod = unique_module(e, op)
        req = requirement_for(method, path)
        public = auth_requirement(method, path).startswith("免认证")
        is_admin = path.startswith("/admin/")
        role_hint = ("管理员 token（ADMIN_TEST_ACCOUNT / ADMIN_TEST_PASSWORD 登录后由 fixture 注入 Authorization）"
                     if is_admin else
                     "普通用户 token（APP_TEST_ACCOUNT / APP_TEST_PASSWORD 登录后由 fixture 注入 Authorization）")
        token_ph = "<管理员 token>" if is_admin else "<普通用户 token>"
        params = op.get("parameters") or []
        path_params = [p for p in params if p.get("in") == "path"]
        query_params = [p for p in params if p.get("in") == "query"]
        required_params = [p for p in params if p.get("required") and p.get("in") != "path"]
        body = (op.get("requestBody") or {}).get("content", {}).get("application/json", {})
        body_schema = body.get("schema") or {}
        has_body = bool(body_schema) or bool(e["body_type"])
        kind = classify(method, path, has_body)
        detail_path = path if "{" in path else path + "/{id}"

        cid_base = re.sub(r"[^A-Za-z0-9]+", "_", f"{method}_{path}").strip("_").upper()
        meta = dict(model.get((method, path), {}))

        payload_bits = []
        if path_params:
            names = "、".join(p["name"] for p in path_params)
            taken = "、".join(f"取一条记录的 {pp['name']}" for pp in path_params)
            payload_bits.append(
                f"路径参数 {names}：运行时先调用 GET {path.split('/{')[0]}?{LIST_PAGE} {taken}")
        if query_params:
            bits, others = [], []
            for prm in query_params:
                nm = prm["name"]
                if nm == "page":
                    bits.append("page=1")
                elif nm == "size":
                    bits.append("size=5")
                elif prm.get("required"):   # 必填 query 必须给值，否则源码侧会 500
                    typ = (prm.get("schema") or {}).get("type", "String")
                    bits.append(f"{nm}={_value_by_type(nm, typ, path, list_hint_for(path, gets)).strip(chr(34))}")
                else:
                    others.append(nm)
            if bits:
                payload_bits.append(f"查询参数：{'；'.join(bits)}")
            if others:
                tail = f"；其余可选过滤参数（{'、'.join(others)}）不传，正向用例取全集"
                if payload_bits and payload_bits[-1].startswith("查询参数："):
                    payload_bits[-1] += tail
                else:
                    payload_bits.append(f"查询参数：不传可选过滤条件")
        body_text, body_keys_used = "", []
        if (method, path) in BODY_LITERAL_OVERRIDE:
            payload_bits.append(f"请求体：{BODY_LITERAL_OVERRIDE[(method, path)]}")
            body_keys_used = []
        if (method, path) in AUTH_BODY_OVERRIDE:
            payload_bits.append(f"请求体：{AUTH_BODY_OVERRIDE[(method, path)]}")
            body_keys_used = []
        elif has_body and (method, path) not in BODY_LITERAL_OVERRIDE:
            ov = BODY_KEYS_OVERRIDE.get((method, path))
            if ov:
                meta["biz_fields"] = ov
                meta["dto_required"] = []
            body_text, body_keys_used = build_body(meta, comps, body_schema, path,
                                                   list_hint_for(path, gets))
            payload_bits.append(f"请求体：{body_text}" if body_text
                                else f"请求体：{e['body_type'] or '按源码签名构造'}")
        payload_text = "；".join(payload_bits) if payload_bits else "无参数"
        ct = "Content-Type: application/json；" if has_body else ""
        auth_hdr = f"Authorization: Bearer {token_ph}"
        hdr_full = f"{ct}{auth_hdr}"
        hdr_anon = f"{ct}不含 Authorization".rstrip("；")

        # ---------- 1. 正向 ----------
        is_export = path.endswith("/export")
        resp200 = (op.get("responses", {}).get("200") or {})
        content = resp200.get("content") or {}
        resp_schema = ((content.get("*/*") or {}).get("schema")
                       or (content.get("application/json") or {}).get("schema") or {})
        vkind, vtarget = ("self", "") if method == "GET" else resolve_verify(method, path, gets)
        KEY_PREFER = ("name", "code", "title", "label", "username", "key", "value", "billNo")
        cand = list(meta.get("dto_required") or []) + list(body_keys_used) + list(meta.get("biz_fields") or [])
        key_field = next((k for k in KEY_PREFER if k in cand), None) or next(
            (k for k in cand if k not in AUDIT_FIELDS), "id")
        status_family = ("status" in body_keys_used) or bool(re.search(r"/(on|off|status|batch-off)$", path))
        anchor = ANCHOR_OVERRIDE.get(path) or anchor_text(method, path, vkind, vtarget, key_field,
                                                          status_family=status_family,
                                                          paged=vtarget in paged_paths)
        if not anchor or vkind == "self":
            anchor = positive_anchor(method, path, resp_schema, comps, detail_path,
                                     is_paged=any(p["name"] in ("page", "size") for p in query_params))
        result = VERB_RESULT.get((method, kind), "操作成功")
        if path in ("/admin/auth/login", "/api/home", "/api/cart/items", "/api/orders", "/api/payment/sandbox"):
            pri = "P0"
        elif kind in ("login", "create", "action") and not is_admin:
            pri = "P0"
        else:
            pri = "P1"
        data_note = "环境既有数据；准备方式：只读查询，无需造数" if method == "GET" else "专属测试数据；准备方式：先调用对应创建接口构造"
        pre_pos = (f"环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                   f"账号：{role_hint}<br>"
                   f"数据：{data_note}")
        cleanup_pos = cleanup_for(method, path, kind, dels)
        if is_export:
            emit(f"{cid_base}-001", pri, "正向", f"{mod}_典型请求_导出成功", pre_pos, method, path,
                 hdr_anon if public else hdr_full, payload_text,
                 "发送请求；记录响应；按预期断言", "200", "无（导出响应为文件流）",
                 "HTTP=200；响应为文件流或表格文本且响应体非空", cleanup_pos,
                 f"接口文档§2；需求：{req}（源码导出接口返回文件流，非统一响应信封）")
        else:
            emit(f"{cid_base}-001", pri, "正向", f"{mod}_典型请求_{result}", pre_pos, method, path,
                 hdr_anon if public else hdr_full, payload_text,
                 "发送请求；记录响应；按预期断言", "200", "0",
                 f"HTTP=200；code=0；{anchor}", cleanup_pos, f"接口文档§2；需求：{req}")
        stats["正向"] += 1

        # ---------- 2. 未带 Token ----------
        if not public:
            emit(f"{cid_base}-002", "P0", "鉴权", f"{mod}_未带Token_拒绝访问",
                 "环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                 "账号：不发送 Authorization（验证未登录场景）<br>"
                 "数据：无需额外数据；准备方式：不构造",
                 method, path, hdr_anon, payload_text,
                 "删除 Authorization 头；发送请求；记录响应", "401", "UNAUTHORIZED",
                 "HTTP=401；error.code=UNAUTHORIZED；响应不含业务数据且不泄露内部堆栈",
                 "无需清理（只读）", f"接口文档§2；需求：{req}")
            stats["鉴权"] += 1

        # ---------- 3. 缺少必填参数 ----------
        if path_params:
            missing = path_params[0]["name"]
            is_trailing = path.rstrip("/").endswith("{" + missing + "}")
            if is_trailing:
                http_, code_ = "404", "NOT_FOUND"
                ass = ("HTTP=404；error.code=NOT_FOUND（实测：省略末段路径参数后路由未命中，"
                       "由 NoResourceFoundException 映射）")
                detail = "省略 URL 末段路径参数（形如 /admin/users/）"
            else:
                http_, code_ = "400", "无（框架级：安全过滤器拒绝空路径段）"
                ass = ("HTTP=400；error.code=无（框架级）；响应为框架错误体，不含业务数据"
                       "（实测：中段路径参数被省略时请求在路由前即被拒绝，不进入业务逻辑）")
                detail = "省略中段路径参数（形如 /admin/users//status，空路径段被安全过滤器拒绝）"
            emit(f"{cid_base}-003", "P1", "参数", f"{mod}_缺少路径参数{missing}_提示资源不存在",
                 f"环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                 f"账号：{role_hint}<br>"
                 f"数据：不构造目标资源；准备方式：{detail}",
                 method, path, hdr_full,
                 f"不提供路径参数 {missing}（URL 中省略该路径段），验证请求被拒绝",
                 "省略路径参数段发送请求；记录响应", http_, code_, ass,
                 "无需清理（只读）", f"接口文档§2；需求：{req}；预期依据：源码路由行为实测")
            stats["参数"] += 1
        elif required_params or (has_body and method in ("POST", "PUT", "PATCH")
                                 and model.get((method, path), {}).get("param_enforced")):
            meta = model.get((method, path), {})
            if required_params:
                miss = required_params[0]["name"]
                body_json = schema_to_json(body_schema, comps)
                pl = (f"不提供必填查询参数 {miss}（URL 中省略该 query）；其余查询参数正常提供"
                      if not body_json else
                      f"删除必填参数 {miss}；其余参数正常提供：{body_json}")
                ttl = f"{mod}_缺少必填参数{miss}_提示参数错误"
                http_, code_ = "400", "BAD_REQUEST"
                ass = "HTTP=400；error.code=BAD_REQUEST；响应 message 指明缺失字段且不含内部堆栈"
            elif meta.get("has_valid"):
                pl = "提交空请求体 {}（省略全部 body 字段），验证 Bean 校验被触发"
                ttl = f"{mod}_缺少必填参数_提示参数错误"
                http_, code_ = "400", "BAD_REQUEST"
                ass = "HTTP=400；error.code=BAD_REQUEST；响应 message 指明首个校验失败字段且不含内部堆栈"
            else:
                pl = "提交空请求体 {}（省略全部 body 字段），验证源码显式参数校验被触发"
                ttl = f"{mod}_缺少必填参数_提示参数错误"
                http_, code_ = "200", "BAD_REQUEST"
                ass = "HTTP=200；code=BAD_REQUEST；响应 message 指明缺失字段且不含内部堆栈"
            emit(f"{cid_base}-003", "P1", "参数", ttl,
                 "环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                 f"账号：{role_hint}<br>"
                 "数据：不构造目标资源；准备方式：直接构造缺参请求",
                 method, path, hdr_full, pl,
                 "构造缺参请求；发送请求；记录响应", http_, code_, ass,
                 "无需清理（只读）", f"接口文档§2；需求：{req}")
            stats["参数"] += 1

        # ---------- 4. 重复提交 / 幂等 ----------
        if method in ("POST", "PUT", "DELETE"):
            policy, policy_src = repeat_policy(method, path)
            scen, http_, code_, ass = REPEAT_TEXT[policy]
            rv_kind, rv_target = resolve_verify(method, path, gets)
            if policy == "state_invalid":
                code_ = "AFTER_SALE_STATUS_INVALID" if "after-sales" in path else "ORDER_STATUS_INVALID"
                rv = (f"回查 GET {rv_target} 校验 data.status 未再次变更" if rv_kind == "detail"
                      else "回查详情校验状态未再次变更")
                ass = f"HTTP=200；code={code_}；{rv}"
            if not ass:
                if policy == "idempotent_delete":
                    ass = (f"HTTP=200；code=0；回查 GET {rv_target}?page=1&size=20 校验该记录已不在 data.records 中"
                           if rv_kind == "list" else
                           "HTTP=200；code=0；回查列表校验该记录已不存在")
                    if path == "/api/history":
                        ass = "HTTP=200；code=0；回查 GET /api/history?page=1&size=20 校验 data.total 为 0"
                elif policy == "idempotent":
                    ass = (f"HTTP=200；code=0；回查 GET {rv_target} 校验关键字段与首次提交的值一致"
                           if rv_kind == "detail" else
                           f"HTTP=200；code=0；回查 GET {rv_target}?page=1&size=20 校验目标记录的字段值与首次提交的值一致"
                           if rv_kind == "list" else
                           "HTTP=200；code=0；回查同一资源校验数据与首次提交一致")
                elif policy == "idempotent_nolist":
                    ass = ("HTTP=200；code=0；回查 GET /api/cart 校验 data.items 数量与首次提交后一致"
                           if "cart" in path else
                           "HTTP=200；code=0；回查该资源校验数据与首次提交一致")
            emit(f"{cid_base}-004", "P1", "幂等", f"{mod}_{scen}",
                 "环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                 f"账号：{role_hint}<br>"
                 "数据：首次提交产生的专属测试数据；准备方式：本用例首次提交生成",
                 method, path, hdr_full, payload_text,
                 "使用同一请求连续发送两次；比较两次状态、业务码与副作用",
                 http_, code_, ass,
                 cleanup_for(method, path, kind, dels),
                 f"接口文档§2；需求：{req}；幂等策略：源码 {policy_src}")
            stats["幂等"] += 1

        # ---------- 5. 分页边界 ----------
        if any(p["name"] in ("page", "size") for p in query_params):
            page_payload = "查询参数：page=0&size=0（边界值）"
            if path_params:
                names = "、".join(p["name"] for p in path_params)
                page_payload = (f"路径参数 {names}：运行时先调用 GET {path.split('/{')[0]}?{LIST_PAGE} 取一条记录的 id；"
                                + page_payload)
            emit(f"{cid_base}-005", "P2", "边界", f"{mod}_非法分页参数_正常返回",
                 "环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。<br>"
                 f"账号：{role_hint}<br>"
                 "数据：环境既有列表数据；准备方式：只读查询",
                 method, path, auth_hdr, page_payload,
                 "分别以 page=0&size=0 与 page=1&size=5 查询；比较两次响应的 total",
                 "200", "0",
                 "HTTP=200；code=0；两次响应的 data.total 一致且 data.records 均为数组（不得 500）",
                 "无需清理（只读）", f"接口文档§2；需求：{req}")
            stats["边界"] += 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    header = f"""# 尚品汇（Shang Pivot）接口测试用例清单

> 来源：后端源码（真值层）+ `docs/openapi-v3-api-docs.json`；生成器 `api_testcases/scripts/generate_cases.py`。
> 规范：`docs/测试资料/rule.md` v2.6；领域词典：`docs/测试资料/领域词典.md`。
> 成功业务码为 `0`；业务失败族走 HTTP 200 + body 数值码（源码 `GlobalExceptionHandler` 无 `@ResponseStatus`）。
> 账号、密码、Token 一律经环境变量提供，不写入本文件。

## 覆盖统计

- 端点：{len(src)}
- 用例总数：{len(rows)}
- 类型分布：{"；".join(f"{k}={v}" for k, v in stats.items())}

## 用例表

| 用例ID | 优先级 | 类型 | 标题 | 前置条件 | 请求方法/URL | 请求头 | Path/Query/Body | 步骤 | 预期HTTP | 预期业务码 | 断言点 | 清理 | 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
"""
    args.out.write_text(header + "\n".join(rows) + "\n", encoding="utf-8")
    print(json.dumps({"endpoints": len(src), "cases": len(rows), "types": stats}, ensure_ascii=False))


if __name__ == "__main__":
    main()
