#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""断言规范化 v2.6（rule.md §3.6）：三要素化 + 弱断言强断言化。

- 机械变换：`HTTP 状态码为 X；响应业务码/错误码符合 Y` → `HTTP=X；code=Y / error.code=Y`
- 业务行 code 列拆分：`200；说明` → code=200，说明并入断言
- 逐条覆盖（OVERRIDES）：23 条 -005 幂等模板行 + 特殊行（按后端源码语义）
- --dry-run：只报差异与残留，不写文件

用法：python3 scripts/assertion_upgrade_v26.py [--dry-run]
"""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import case_lint as L  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
INV = ROOT / "test_cases_inventory.md"

DICT_ENUMS = {
    "UNAUTHORIZED", "FORBIDDEN", "VALIDATION_FAILED", "NOT_FOUND", "PRODUCT_NOT_FOUND",
    "SKU_NOT_FOUND", "SKU_REQUIRED", "ORDER_NOT_FOUND", "ORDER_STATE_INVALID", "STATE_INVALID",
    "PAYMENT_FAILED", "INSUFFICIENT_STOCK", "INVALID_QUANTITY", "CART_EMPTY", "COUPON_INVALID",
    "INTERNAL_ERROR",
}

# ---------------------------------------------------------------- 逐条覆盖
# 依据：后端源码（com/web/controller|service/impl）重复提交/删除/取消语义；
#      接口文档错误约定（失败=HTTP 400 + error 信封，冲突处记观察项）。
OVERRIDES: dict[str, dict[str, str]] = {
    # --- 幂等/重复 -005（契约） ---
    "POST_V1_AUTH_LOGIN-005": {
        "assertions": "HTTP=200；code=200；回查 POST /v1/auth/login 两次响应的 data.token 均非空、data.user.id 相同（两次 token 均可独立使用）；不产生额外副作用。",
    },
    "POST_V1_ADMIN_AUTH_LOGIN-005": {
        "assertions": "HTTP=200；code=200；回查 POST /v1/admin/auth/login 两次响应的 data.token 均非空、data.user.role=ADMIN（两次 token 均可独立使用）；不产生额外副作用。",
    },
    "POST_V1_AUTH_REGISTER-005": {
        "title": "注册_重复提交_第二次被拒绝",
        "expected_http": "400",
        "expected_code": "VALIDATION_FAILED",
        "assertions": "HTTP=400；error.code=VALIDATION_FAILED；第二次注册被拒绝（账号已存在）；未产生重复账号；错误信息不泄露内部堆栈。",
    },
    "POST_V1_CART_ITEMS-005": {
        "title": "加入购物车_重复添加_不产生重复记录",
        "assertions": "HTTP=200；code=200；回查 GET /v1/cart 校验该商品仅一条记录且 data.items[].quantity=2（重复添加按数量合并累计）；不产生未声明副作用。",
    },
    "PUT_V1_CART_ITEMS_ID-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/cart 校验该行 data.items[].quantity=2（覆盖式更新，重复提交不叠加）；不产生未声明副作用。",
    },
    "POST_V1_COUPONS_CODE_CHECK-005": {
        "assertions": "HTTP=200；code=200；回查再次调用本接口（POST /v1/coupons/{code}/check），校验两次响应的 data 子树完全相同（券校验为只读操作）；不产生未声明副作用。",
    },
    "POST_V1_ME_ADDRESSES-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/me/addresses 校验相同 detail 的地址出现 2 条（源码未做重复地址校验）；isDefault 规则不受影响；不产生未声明副作用。",
    },
    "PUT_V1_ME_ADDRESSES_ID-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/me/addresses 校验该 id 记录的 receiver/phone/region/detail 等于提交值（重复提交不改变结果）；不产生未声明副作用。",
    },
    "POST_V1_NOTIFICATIONS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/notifications 校验相同 title 的通知出现 2 条（源码未做重复通知校验）；不产生未声明副作用。",
    },
    "POST_V1_ORDERS_CHECKOUT-005": {
        "title": "结算下单_重复提交_第二次被拒绝",
        "expected_http": "400",
        "expected_code": "CART_EMPTY",
        "assertions": "HTTP=400；error.code=CART_EMPTY；第二次结算被拒绝（首次结算已清空选中购物车项，订单不重复生成）；未产生额外订单与库存扣减；错误信息不泄露内部堆栈。",
    },
    "POST_V1_PAYMENTS_ORDERID_PAY-005": {
        "assertions": "HTTP=200；code=200；data.tradeId 非空且与首次发起不同；回查 GET /v1/payments/{orderId}/status 校验 data.status=PENDING；重复发起各生成一笔支付记录（源码未做重复发起拦截）；不产生未声明副作用。",
    },
    "POST_V1_PRODUCTS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/products 校验两次创建各产生一条同名商品记录（源码未做名称唯一性校验）；不产生未声明副作用。",
    },
    "POST_V1_REVIEWS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/reviews 校验同一订单出现 2 条相同 content 的评价（源码未拦截重复评价）；不产生未声明副作用。",
    },
    "POST_V1_AFTERSALES_APPLY-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/aftersales 校验同一 orderItemId 出现 2 条 SUBMITTED 记录（源码未拦截重复申请，已列观察项待拍板）；不产生未声明副作用。",
    },
    "POST_V1_ADMIN_PRODUCTS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/products 校验两次创建各产生一条同名商品记录（源码未做名称唯一性校验）；不产生未声明副作用。",
    },
    "POST_V1_ADMIN_CATEGORIES-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/categories 校验两次创建各产生一条同名类目记录（源码未做名称唯一性校验）；不产生未声明副作用。",
    },
    "POST_V1_ADMIN_ORDERS_ID_SHIP-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/orders/{id} 校验 data.status=2 且物流单号等于提交值（重复发货状态不变）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_PRODUCTS_ID-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/products/{id} 校验 data.name/price/stock 等于提交值（第二次提交不改变结果）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_PRODUCTS_ID_STATUS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/products/{id} 校验 data.status=0（重复提交状态不变）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_USERS_ID_STATUS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/users/{id} 校验 data.status=0（重复提交状态不变）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_CATEGORIES_ID-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/categories/{id} 校验 data.name 等于提交值（重复提交不改变结果）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_ORDERS_ID_STATUS-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/orders/{id} 校验 data.status=2（重复提交状态不变）；不产生未声明副作用。",
    },
    "PUT_V1_ADMIN_AFTERSALES_ID_REVIEW-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/aftersales/{id} 校验 data.status=APPROVED、data.admin_remark 等于提交值（重复审核不改变结果）；不产生未声明副作用。",
    },
    # --- 重复删除/取消（错误约定冲突 → 按文档预期 + 观察项） ---
    "DELETE_V1_CART_ITEMS_ID-005": {
        "expected_code": "NOT_FOUND",
        "assertions": "HTTP=400；error.code=NOT_FOUND；第二次删除被拒绝、数据无副作用（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；错误信息不泄露内部堆栈。",
    },
    "DELETE_V1_ME_ADDRESSES_ID-005": {
        "expected_code": "NOT_FOUND",
        "assertions": "HTTP=400；error.code=NOT_FOUND；第二次删除被拒绝、数据无副作用（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；错误信息不泄露内部堆栈。",
    },
    "DELETE_V1_FAVORITES_FAVID-005": {
        "expected_code": "NOT_FOUND",
        "assertions": "HTTP=400；error.code=NOT_FOUND；第二次删除被拒绝、数据无副作用（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；错误信息不泄露内部堆栈。",
    },
    "DELETE_V1_FAVORITES_PRODUCT_PRODUCTID-005": {
        "expected_code": "NOT_FOUND",
        "assertions": "HTTP=400；error.code=NOT_FOUND；第二次删除被拒绝、数据无副作用（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；错误信息不泄露内部堆栈。",
    },
    "DELETE_V1_FAVORITES_BULK-005": {
        "expected_code": "NOT_FOUND",
        "assertions": "HTTP=400；error.code=NOT_FOUND；重复批量删除被拒绝、数据无副作用（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；错误信息不泄露内部堆栈。",
    },
    "DELETE_V1_NOTIFICATIONS-005": {
        "title": "清空通知_重复删除_无新副作用",
        "expected_http": "200",
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；data.count=0（第二次清空无数据）；清空操作幂等、不产生未声明副作用。",
    },
    "DELETE_V1_ADMIN_PRODUCTS_ID-005": {
        "title": "管理员删除商品_重复删除_无新副作用",
        "expected_http": "200",
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/products/{id} 校验商品不存在；重复删除同样返回成功（源码未做存在性校验，幂等语义）；不产生未声明副作用。",
    },
    "DELETE_V1_ADMIN_CATEGORIES_ID-005": {
        "title": "管理员删除类目_重复删除_无新副作用",
        "expected_http": "200",
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/categories/{id} 校验类目不存在；重复删除同样返回成功（源码未做存在性校验，幂等语义）；不产生未声明副作用。",
    },
    "POST_V1_ORDERS_ID_CANCEL-005": {
        "title": "取消订单_重复取消_第二次被拒绝",
        "expected_http": "400",
        "expected_code": "ORDER_STATE_INVALID",
        "assertions": "HTTP=400；error.code=ORDER_STATE_INVALID；重复取消被拒绝、订单状态保持已取消（源码现状：返回 HTTP 200 + 数值码 500，与接口文档错误约定冲突，已列观察项）；不产生未声明副作用。",
    },
    "POST_V1_AFTERSALES_ID_CANCEL-005": {
        "title": "取消售后_重复取消_第二次被拒绝",
        "expected_code": "STATE_INVALID",
        "assertions": "HTTP=400；error.code=STATE_INVALID；重复取消被拒绝（状态非 SUBMITTED/PROCESSING）；回查 GET /v1/aftersales/{id} 校验 data.status 保持 CANCELLED；不产生未声明副作用。",
    },
    # --- BUS 特殊行 ---
    "BUS-AUTH-001": {
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；data.user 含 id/account/nickname/role/status 字段、不含 password；响应不泄露内部堆栈。",
    },
    "BUS-ORD-002": {
        "expected_code": "INSUFFICIENT_STOCK",
        "assertions": "HTTP=400；error.code=INSUFFICIENT_STOCK；回查 GET /v1/orders 确认未生成订单、库存未扣减；响应不泄露内部堆栈。",
    },
    "BUS-ORD-004": {
        "expected_code": "404（body 数值码，越权视为不存在）",
        "assertions": "HTTP=200；body.code=404（越权访问视为订单不存在）；响应 data 字段不含他人订单信息；不泄露他人数据。",
    },
    "BUS-PAY-002": {
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；回查 GET /v1/payments/{orderId}/status 校验 data.status=SUCCESS 且仅入账一次（重复回调不重复入账）；响应不泄露内部堆栈。",
    },
    # --- 认证响应复合行 ---
    "BUS-AUTH-004": {
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；data.user 字段包含 id/account/nickname/role/status（后端不限制手机号/邮箱格式）、不含 password；响应不泄露内部堆栈。",
    },
    "BUS-AUTH-005": {
        "expected_code": "200",
        "assertions": "HTTP=200；code=200；data.token 非空、data.expiresIn=7200、data.user 字段包含 id/account/nickname/role/status 且不含 password；响应不泄露内部堆栈。",
    },
    "BUS-PRD-002": {
        "expected_code": "404（body 数值码，文档约定）",
        "assertions": "HTTP=404；code=404（body 数值码，文档约定 Product not found）；不返回商品数据；响应不泄露内部堆栈。",
    },
    "BUS-PRD-003": {
        "expected_http": "400",
        "expected_code": "UNAUTHORIZED",
        "assertions": "HTTP=400；error.code=UNAUTHORIZED；未认证请求被拒绝、未创建商品（源码现状：POST /v1/products 未拦截未认证写入，属安全缺陷，已列观察项待拍板）；错误信息不泄露内部堆栈。",
    },
    # --- 分页归一化 ×10 ---
    "GET_V1_PRODUCTS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_REVIEWS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_NOTIFICATIONS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ORDERS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_AFTERSALES-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_PRODUCTS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_ORDERS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_USERS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_AFTERSALES-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_OPERATION_LOGS-004": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id；重复请求两次 data 长度与首项 id 一致（分页归一化后行为稳定）；不 500；响应不泄露内部堆栈。",
    },
    # --- 典型请求 001 行（数据锚点补强） ---
    "GET_V1_PRODUCTS-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id/name/price/stock；分页结果 data 长度与 page/size 一致；响应不泄露内部堆栈。",
    },
    "POST_V1_PRODUCTS-001": {
        "assertions": "HTTP=200；code=200；data.id 非空、data.name 等于提交值；回查 GET /v1/products 校验新增商品字段包含 id/name/price/stock；响应不泄露内部堆栈。",
    },
    "GET_V1_REVIEWS-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id/rating/content/productId；响应不泄露内部堆栈。",
    },
    "POST_V1_REVIEWS-001": {
        "assertions": "HTTP=200；code=200；data.id 非空；回查 GET /v1/reviews 校验新增记录 data 中 rating=5、content 等于提交内容；响应不泄露内部堆栈。",
    },
    "GET_V1_ME-001": {
        "assertions": "HTTP=200；code=200；data 字段包含 id/account/nickname/role/status 且不含 password；响应不泄露内部堆栈。",
    },
    "POST_V1_AUTH_REGISTER-001": {
        "assertions": "HTTP=200；code=200；data.user 字段包含 id/account/nickname/role/status 且不含 password；响应不泄露内部堆栈。",
    },
    "GET_V1_ME_ADDRESSES-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id/receiver/phone/region/detail/isDefault；响应不泄露内部堆栈。",
    },
    "POST_V1_ME_ADDRESSES-001": {
        "assertions": "HTTP=200；code=200；data.id 非空；回查 GET /v1/me/addresses 校验新增记录 receiver/detail 等于提交值；响应不泄露内部堆栈。",
    },
    "PUT_V1_ME_ADDRESSES_ID-001": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/me/addresses 校验该 id 记录 receiver/phone/region/detail 等于提交值；响应不泄露内部堆栈。",
    },
    "DELETE_V1_ME_ADDRESSES_ID-001": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/me/addresses 校验该 id 记录已不存在；响应不泄露内部堆栈。",
    },
    "GET_V1_CATEGORIES-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id/name/parent_id，层级结构完整；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_OPERATION_LOGS-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id、操作者、动作、时间；分页结构完整；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_DASHBOARD_STATS-001": {
        "assertions": "HTTP=200；code=200；data 字段包含数值型统计项（字段值为数字）；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_AFTERSALES-001": {
        "assertions": "HTTP=200；code=200；data 为数组且字段包含 id/status/adminRemark；响应不泄露内部堆栈。",
    },
    "GET_V1_ADMIN_AFTERSALES_ID-001": {
        "assertions": "HTTP=200；code=200；data.status 属于 SUBMITTED/PROCESSING/APPROVED/REJECTED/CANCELLED 枚举、data 字段包含 id/adminRemark；响应不泄露内部堆栈。",
    },
    "PUT_V1_ADMIN_AFTERSALES_ID_REVIEW-001": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/aftersales/{id} 校验 data.status=APPROVED、data.adminRemark 等于提交值；响应不泄露内部堆栈。",
    },
    "POST_V1_PAYMENTS_WEBHOOK-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/payments/{orderId}/status 校验 data.status=SUCCESS 且仅一笔入账（重复回调不重复入账）；不产生额外数据副作用。",
    },
    "POST_V1_NOTIFICATIONS_ID_READ-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/notifications 校验该通知 data.isRead=true、未读数量不因重复标记变化；不产生额外数据副作用。",
    },
    "POST_V1_NOTIFICATIONS_MARKALLREAD-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/notifications 校验全部通知 data.isRead=true、未读数量不再变化；不产生额外数据副作用。",
    },
    "POST_V1_FAVORITES-005": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/favorites 校验该商品仅一条收藏记录（重复添加不产生重复记录）；不产生额外数据副作用。",
    },
    "BUS-ADM-003": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/admin/aftersales/{id} 校验 data.status 属于 APPROVED/REJECTED 且 data.adminRemark 等于提交值；响应不泄露内部堆栈。",
    },
    "BUS-ME-001": {
        "assertions": "HTTP=200；code=200；data 字段包含 id/account/nickname/role/status 且不含 password；响应不泄露内部堆栈。",
    },
    "BUS-ME-002": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/me/addresses 校验 data 中 isDefault=1 的记录不超过 1 条、字段包含 receiver/phone/region/detail；响应不泄露内部堆栈。",
    },
    "BUS-PRD-001": {
        "assertions": "HTTP=200；code=200；回查两次调用 GET /v1/products 校验 data 长度与首项 id 一致（稳定分页）；不 500；响应不泄露内部堆栈。",
    },
    "BUS-CART-001": {
        "assertions": "HTTP=200；code=200；回查 GET /v1/cart 校验同商品同规格仅一条记录且 data.items[].quantity=2；不产生额外数据副作用。",
    },
}


def fix_assertions(a: str, http: str) -> str:
    """机械三要素化：旧两种头部 → 新格式。"""
    # c. `...符合 HTTP 200 + code=200`
    a = re.sub(r"HTTP 状态码为 (\d{3})；响应业务码/错误码符合 HTTP \1 \+ code=200",
               r"HTTP=\1；code=200", a)
    # a/b. 正向 200（可带括注）
    a = re.sub(r"HTTP 状态码为 200；响应业务码/错误码符合 200(（[^）]*）)?",
               lambda m: "HTTP=200；code=200" + (m.group(1) or ""), a)
    # d. 负向大写枚举
    a = re.sub(r"HTTP 状态码为 (\d{3})；响应业务码/错误码符合 ([A-Z][A-Z_]+)",
               r"HTTP=\1；error.code=\2", a)
    # e. 框架级 404
    a = re.sub(r"HTTP 状态码为 404；响应业务码/错误码符合 HTTP 404；响应非 500 且为 404；",
               "HTTP=404；error.code=无（框架级 404）；", a)
    a = re.sub(r"HTTP 状态码为 404；响应业务码/错误码符合 HTTP 404；",
               "HTTP=404；error.code=无（框架级 404）；", a)
    return a


def fix_code_column(code: str) -> tuple[str, str]:
    """业务码列规范化：框架级 404 去除 HTTP 重述；`200；说明` → ('200', '说明')。"""
    if code.strip() == "404（非 500 且为 404）":
        return "无（框架级 404）", ""
    m = re.match(r"^(200|无（[^）]*）|[A-Z][A-Z_]+（[^）]*）|[A-Z][A-Z_]+|200（[^）]*）)；(.*)$", code)
    if m:
        return m.group(1), m.group(2)
    return code, ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    lines = INV.read_text(encoding="utf-8").splitlines()
    out_lines, changes = [], []
    problems = []
    for line in lines:
        if not line.startswith("| ") or line.startswith("| 用例ID") or line.startswith("|---"):
            out_lines.append(line)
            continue
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if len(cells) != 14 or not cells[0]:
            out_lines.append(line)
            continue
        cid = cells[0]
        row = {
            "priority": cells[1], "kind": cells[2], "title": cells[3],
            "expected_http": cells[9], "expected_code": cells[10], "assertions": cells[11],
        }
        before = dict(row)

        # 1) 机械：code 列拆分
        new_code, tail = fix_code_column(row["expected_code"])
        row["expected_code"] = new_code
        if tail and tail not in row["assertions"]:
            row["assertions"] = row["assertions"].rstrip("。") + "；" + tail

        # 2) 机械：断言头部三要素化
        row["assertions"] = fix_assertions(row["assertions"], row["expected_http"])

        # 3) 逐条覆盖
        ov = OVERRIDES.get(cid, {})
        for k, v in ov.items():
            row[k] = v

        # 4) 残留检查（与 case_lint R29/R30 判定完全一致）
        if re.search(r"HTTP 状态码为|错误码符合|业务码符合", row["assertions"]):
            problems.append((cid, "断言仍有旧措辞", row["assertions"][:60]))
        a_body = re.sub(r"^(?:(?:HTTP|响应业务码/错误码符合)[^；]*；\s*)+", "", row["assertions"]).strip()
        if row["expected_http"].strip().startswith("200") and not (
                L.R29_DATA_ANCHOR.search(row["assertions"]) or L.R29_FIELD_ANCHOR.search(a_body)):
            problems.append((cid, "正向仍无数据锚点", row["assertions"][:60]))

        if row != before:
            changes.append((cid, before, dict(row)))
        cells[3], cells[9], cells[10], cells[11] = (row["title"], row["expected_http"],
                                                    row["expected_code"], row["assertions"])
        out_lines.append("| " + " | ".join(cells) + " |")

    print(f"变更行数：{len(changes)}")
    for cid, b, a in changes[:200]:
        diffs = [k for k in b if b[k] != a[k]]
        print(f"  {cid[:42]:44s} {','.join(diffs)}")
    if problems:
        print(f"\n⚠ 残留待处理 {len(problems)}:")
        for p in problems:
            print("  ", p)
    if not args.dry_run:
        INV.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
        print("已写回清单。")
    else:
        print("（dry-run，未写回）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
