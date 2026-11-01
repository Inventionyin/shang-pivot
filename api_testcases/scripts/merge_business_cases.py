#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""尚品汇（Shang Pivot）业务链路用例：生成 BUS-* 用例并合并进清单。

覆盖《需求文档》业务编号（PRD/RBAC/STK/ME/MALL/CFG/CUS/INF），补齐契约用例覆盖不到的
业务规则：三态库存不变式、下单幂等与金额规则、支付/取消/确认收货状态机、售后审核流、
评价可见性、定时上架、会员冻结等。

确定性预期来源：后端源码（真值层）。业务失败族走 HTTP 200 + body 数值码。
用法：
  python3 scripts/merge_business_cases.py --inventory test_cases_inventory.md
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

ADMIN = "管理员 token（ADMIN_TEST_ACCOUNT / ADMIN_TEST_PASSWORD 登录后由 fixture 注入 Authorization）"
USER = "普通用户 token（APP_TEST_ACCOUNT / APP_TEST_PASSWORD 登录后由 fixture 注入 Authorization）"
ENVLINE = "环境：baseUrl（BASE_URL）与测试账号由 pytest 环境变量提供（conftest 自动注入）。"


def pre(account: str, data: str) -> str:
    return f"{ENVLINE}<br>账号：{account}<br>数据：{data}"


def steps(method_url: str, headers: str, payload: str, tail: str) -> str:
    return (f"请求：{method_url}<br>请求头：{headers}<br>参数：{payload}<br>步骤：{tail}")


def row(cid, pri, kind, title, pre_txt, method_url, headers, payload, tail,
        http, code, assertions, cleanup, notes) -> str:
    cells = [cid, pri, kind, title, pre_txt, method_url, headers, payload,
             steps(method_url, headers, payload, tail), http, code, assertions, cleanup, notes]
    return "| " + " | ".join(str(c).replace("\n", "<br>").replace("|", "\\|") for c in cells) + " |"


AH = "Content-Type: application/json；Authorization: Bearer <管理员 token>"
UH = "Content-Type: application/json；Authorization: Bearer <普通用户 token>"


def build() -> list[str]:
    r: list[str] = []

    # ---------------- 认证与会员（CUS / ME） ----------------
    r.append(row("BUS-AUTH-001", "P0", "业务",
                 "会员短信登录_未注册手机号_自动注册并签发令牌",
                 pre("无需登录（免认证接口）", "手机号在库中不存在；准备方式：使用未注册手机号 13900009999"),
                 "POST /api/auth/login",
                 "Content-Type: application/json",
                 '{"phone": "13900009999", "code": "123456", "clientType": "web"}',
                 "发送登录请求；记录响应；回查 GET /api/me 校验新会员资料",
                 "200", "0",
                 "HTTP=200；code=0；data.accessToken 非空；回查 GET /api/me 校验 data.phone 等于本次登录手机号",
                 "用例结束后由管理员冻结该临时会员，清理本次注册产生的演示数据",
                 "接口文档§3.1；需求：CUS-01"))
    r.append(row("BUS-AUTH-002", "P0", "业务",
                 "会员短信登录_验证码错误_提示登录失败",
                 pre("无需登录（免认证接口）", "使用已存在手机号；准备方式：种子会员 13800000001"),
                 "POST /api/auth/login", "Content-Type: application/json",
                 '{"phone": "13800000001", "code": "000000", "clientType": "web"}',
                 "发送错误验证码登录请求；记录响应",
                 "200", "LOGIN_FAILED",
                 "HTTP=200；code=LOGIN_FAILED；响应 message 包含「验证码」且不回显手机号明文",
                 "无需清理（只读，登录失败不产生数据）", "接口文档§3.1；需求：CUS-01"))
    r.append(row("BUS-AUTH-003", "P1", "业务",
                 "会员短信登录_冻结会员_提示账号不可用",
                 pre("无需登录（免认证接口）",
                     "已冻结会员；准备方式：先用管理员 token 调用 PUT /admin/customers/{id}/status 冻结种子会员 13500000005"),
                 "POST /api/auth/login", "Content-Type: application/json",
                 '{"phone": "13500000005", "code": "123456", "clientType": "web"}',
                 "使用冻结会员手机号登录；记录响应",
                 "200", "CUSTOMER_FROZEN",
                 "HTTP=200；code=CUSTOMER_FROZEN；响应 message 包含「不可用」",
                 "用例结束后调用 PUT /admin/customers/{id}/status 将该会员还原为正常状态",
                 "接口文档§3.1；需求：CUS-05"))
    r.append(row("BUS-AUTH-004", "P1", "业务",
                 "会员刷新令牌_无效刷新令牌_拒绝访问",
                 pre("无需登录（免认证接口）", "构造无效刷新令牌；准备方式：使用伪造字符串作为 refreshToken"),
                 "POST /api/auth/refresh", "Content-Type: application/json",
                 '{"refreshToken": "invalid.refresh.token"}',
                 "发送刷新请求；记录响应",
                 "200", "TOKEN_INVALID",
                 "HTTP=200；code=TOKEN_INVALID；响应不含业务数据",
                 "无需清理（只读）", "接口文档§3.1；需求：CUS-02"))
    r.append(row("BUS-ME-001", "P1", "业务",
                 "会员修改资料_修改昵称_资料更新成功",
                 pre(USER, "会员当前昵称已知；准备方式：登录后先调用 GET /api/me 记录原昵称用于还原"),
                 "PUT /api/me", UH, '{"nickname": "自动化测试昵称"}',
                 "提交新昵称；记录响应；回查 GET /api/me 校验昵称",
                 "200", "0",
                 "HTTP=200；code=0；data.nickname 等于「自动化测试昵称」；回查 GET /api/me 校验 data.nickname 与提交值一致",
                 "用例结束后调用 PUT /api/me 还原原昵称", "接口文档§3.2；需求：CUS-04"))
    r.append(row("BUS-ME-002", "P1", "业务",
                 "会员上传头像_两步式上传_资料返回头像对象键",
                 pre(USER, "已登录会员；准备方式：登录后直接调用预签名接口，不依赖既有数据"),
                 "POST /api/files/presign", UH,
                 '{"bizType": "AVATAR", "contentType": "image/png", "size": 1024, "filename": "avatar.png"}',
                 "获取预签名地址；上传文件字节；调用确认接口；回查 GET /api/me",
                 "200", "0",
                 "HTTP=200；code=0；data.objectKey 非空；回查 GET /api/me 校验头像字段指向本次上传的对象键",
                 "用例结束后调用 PUT /api/me 清空头像，并删除该对象记录",
                 "接口文档§3.2；需求：ME-03"))
    r.append(row("BUS-ME-003", "P1", "业务",
                 "会员收藏商品_重复收藏_不产生重复记录",
                 pre(USER, "上架商品；准备方式：先调用 GET /api/products?page=1&size=1 取一条在售商品 spuId"),
                 "POST /api/favorites/{spuId}", UH,
                 "路径参数 spuId：运行时先调用 GET /api/products?page=1&size=5 取一条记录的 id",
                 "连续两次收藏同一商品；回查 GET /api/favorites 校验列表",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/favorites 校验该商品在 data 列表中仅出现一次",
                 "用例结束后调用 DELETE /api/favorites/{spuId} 取消收藏",
                 "接口文档§3.2；需求：ME-01"))
    r.append(row("BUS-ME-004", "P2", "业务",
                 "会员浏览足迹_重复浏览同一商品_仅保留最新一条",
                 pre(USER, "在售商品；准备方式：先调用 GET /api/products?page=1&size=1 取一条商品 spuId 并连续访问详情两次"),
                 "GET /api/history", "Authorization: Bearer <普通用户 token>",
                 "查询参数：page=1；size=5",
                 "查询浏览足迹列表；核对同一商品的记录条数",
                 "200", "0",
                 "HTTP=200；code=0；data.records 中同一商品的足迹记录仅一条，且 lastAt 为最近一次浏览时间",
                 "用例结束后调用 DELETE /api/history 清理全部浏览足迹",
                 "接口文档§3.2；需求：ME-02"))

    # ---------------- 库存三态（STK） ----------------
    r.append(row("BUS-STK-001", "P0", "业务",
                 "提交订单_下单锁定库存_物理库存不变且锁定增加",
                 pre(USER, "可售库存充足的在售商品与默认地址；准备方式：先取 GET /api/products?page=1&size=1 的商品 id 加购，再取 GET /api/addresses 的默认地址 id"),
                 "POST /api/orders", UH,
                 '{"addressId": 地址 id（先调用 GET /api/addresses 取一条记录的 id）, '
                 '"idempotentKey": "STK001-唯一键", "cartItemIds": [购物车项 id（先调用 GET /api/cart 取）]}',
                 "记录下单前 GET /admin/stocks 的可售数量；提交订单；回查库存变化",
                 "200", "0",
                 "HTTP=200；code=0；data.orderNo 非空；回查 GET /admin/stocks 校验该 SKU 的 locked_stock 增加订单数量、可售数量相应减少",
                 "用例结束后调用 POST /api/orders/{id}/cancel 取消订单释放锁定库存",
                 "接口文档§3.5；需求：STK-01"))
    r.append(row("BUS-STK-002", "P0", "业务",
                 "取消订单_待支付订单_关闭订单并解锁库存",
                 pre(USER, "待支付订单；准备方式：本用例先提交一笔订单获得订单 id"),
                 "POST /api/orders/{id}/cancel", "Authorization: Bearer <普通用户 token>",
                 "路径参数 id：运行时先调用 GET /api/orders?page=1&size=5 取一条记录的 id",
                 "取消订单；回查订单详情与库存",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/orders/{id} 校验 data.status 为 CLOSED；回查 GET /admin/stocks 校验锁定库存回落",
                 "无需清理（订单已关闭，库存已释放）", "接口文档§3.5；需求：STK-01"))
    r.append(row("BUS-STK-003", "P1", "业务",
                 "管理员调整库存_调整后物理库存低于锁定库存_提示参数错误",
                 pre(ADMIN, "存在锁定库存的 SKU；准备方式：先取 GET /admin/stocks?page=1&size=1 的可售与锁定数量，再按超出锁定的负调整量提交"),
                 "PUT /admin/stocks/adjust", AH,
                 '{"skuId": SKU id（先调用 GET /admin/stocks 取一条记录的 id）, "warehouseId": 仓库 id（先调用 GET /admin/warehouses 取）, "changeQty": 负数且使物理库存低于锁定}',
                 "提交非法调整；记录响应；回查库存未变化",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /admin/stocks 校验该 SKU 的 physical_stock 与 locked_stock 未变化",
                 "无需清理（只读，非法请求不落库）", "接口文档§2.5；需求：STK-03"))
    r.append(row("BUS-STK-004", "P1", "业务",
                 "管理员发货_待发货订单_扣减物理库存并写入出库单",
                 pre(ADMIN, "待发货订单；准备方式：本用例先用沙箱支付一笔订单使其进入待发货，再发货"),
                 "POST /admin/orders/{id}/ship", "Content-Type: application/json；Authorization: Bearer <管理员 token>",
                 '路径参数 id：运行时先调用 GET /admin/orders?page=1&size=5 取一条状态为待发货的订单 id；'
                 '请求体：{"trackingNo": "SF1234567890"}',
                 "发货；回查订单详情与出入库单",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /admin/orders/{id} 校验 status 为 PENDING_RECEIVE；回查 GET /admin/in-out?page=1&size=5 校验生成类型为 SALE_OUT 的单据",
                 "无需清理（状态类动作不可撤销，使用本用例专属订单）", "接口文档§2.6；需求：STK-05"))

    # ---------------- 购物车（MALL-03） ----------------
    r.append(row("BUS-CART-001", "P0", "业务",
                 "加入购物车_同规格重复加购_数量累加",
                 pre(USER, "在售商品；准备方式：先调用 GET /api/products?page=1&size=1 取一条在售商品的 SKU id"),
                 "POST /api/cart/items", UH,
                 "请求体：{skuId 先调用 GET /api/products 取详情中的 SKU id, quantity: 1}",
                 "连续两次以数量 1 加购同一规格；回查购物车",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/cart 校验 data.items 中该规格 quantity 等于 2",
                 "用例结束后调用 DELETE /api/cart/items/{id} 移除该行",
                 "接口文档§3.4；需求：MALL-03"))
    r.append(row("BUS-CART-002", "P1", "业务",
                 "加入购物车_数量为零_提示参数错误",
                 pre(USER, "在售商品；准备方式：先调用 GET /api/products?page=1&size=1 取一条在售商品的 SKU id"),
                 "POST /api/cart/items", UH,
                 "请求体：{skuId 先调用 GET /api/products 取详情中的 SKU id, quantity: 0}",
                 "提交数量为 0 的加购请求；回查购物车",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /api/cart 校验该规格未新增记录",
                 "无需清理（只读，非法请求不落库）", "接口文档§3.4；需求：MALL-03"))
    r.append(row("BUS-CART-003", "P1", "业务",
                 "加入购物车_商品已下架_提示商品不可用",
                 pre(USER, "已下架商品；准备方式：先用管理员 token 调用 PUT /admin/products/{id}/off 下架一条草稿→上架→下架的专属商品，取其 SKU id"),
                 "POST /api/cart/items", UH,
                 "请求体：{skuId 为已下架商品的 SKU id, quantity: 1}",
                 "对已下架商品加购；记录响应",
                 "200", "PRODUCT_UNAVAILABLE",
                 "HTTP=200；code=PRODUCT_UNAVAILABLE；回查 GET /api/cart 校验未新增该商品记录",
                 "用例结束后调用 DELETE /admin/products/{id} 删除该专属商品",
                 "接口文档§3.4；需求：MALL-03"))
    r.append(row("BUS-CART-004", "P1", "业务",
                 "修改购物车数量_数量小于等于零_提示参数错误",
                 pre(USER, "购物车存在有效行；准备方式：先加购一条专属商品并记录 GET /api/cart 的行 id"),
                 "PUT /api/cart/items/{id}", UH,
                 '路径参数 id：运行时先调用 GET /api/cart?page=1&size=5 取一条记录的 id；请求体：{"quantity": 0}',
                 "提交数量为 0 的修改请求；回查购物车行数量",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /api/cart 校验该行 quantity 保持原值",
                 "用例结束后调用 DELETE /api/cart/items/{id} 移除该行",
                 "接口文档§3.4；需求：MALL-03"))
    r.append(row("BUS-CART-005", "P2", "业务",
                 "清理失效购物车项_存在失效行_移除失效并保留有效行",
                 pre(USER, "购物车同时存在有效行与失效行；准备方式：加购两条专属商品后由管理员下架其中一条"),
                 "POST /api/cart/clear-invalid", "Authorization: Bearer <普通用户 token>",
                 "无请求体",
                 "清理失效项；回查购物车列表",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/cart 校验失效商品行已移除且有效行仍存在",
                 "用例结束后调用 DELETE /api/cart/items/{id} 移除剩余行并删除专属商品",
                 "接口文档§3.4；需求：MALL-03"))

    # ---------------- 订单与支付（MALL-05/06/07/08/09） ----------------
    r.append(row("BUS-ORD-001", "P0", "业务",
                 "提交订单_同幂等键重复提交_不重复下单",
                 pre(USER, "可售商品与默认地址；准备方式：先加购一条专属商品并取默认地址 id"),
                 "POST /api/orders", UH,
                 '{"addressId": 地址 id（先调用 GET /api/addresses 取）, "idempotentKey": "ORD001-固定唯一键", "cartItemIds": [购物车项 id]}',
                 "使用同一幂等键连续提交两次；回查订单列表",
                 "200", "0",
                 "HTTP=200；code=0；两次响应的 data.orderNo 相同；回查 GET /api/orders 校验 data.total 未因第二次提交而增加",
                 "用例结束后调用 POST /api/orders/{id}/cancel 关闭该订单",
                 "接口文档§3.5；需求：MALL-05"))
    r.append(row("BUS-ORD-002", "P1", "业务",
                 "提交订单_购物车无可结算商品_提示参数错误",
                 pre(USER, "购物车为空或无选中行；准备方式：使用已清空购物车的专属会员账号"),
                 "POST /api/orders", UH,
                 '{"addressId": 地址 id（先调用 GET /api/addresses 取）, "idempotentKey": "ORD002-唯一键", "cartItemIds": []}',
                 "提交无可结算商品的订单；记录响应",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /api/orders 校验未新增订单",
                 "无需清理（只读，非法请求不落库）", "接口文档§3.5；需求：MALL-05"))
    r.append(row("BUS-ORD-003", "P0", "业务",
                 "提交订单_购物车金额达到免邮门槛_运费为零",
                 pre(USER, "商品金额达到免邮门槛；准备方式：加购金额不小于 99 元的专属商品并取默认地址 id"),
                 "POST /api/orders", UH,
                 '{"addressId": 地址 id（先调用 GET /api/addresses 取）, "idempotentKey": "ORD003-唯一键", "cartItemIds": [购物车项 id]}',
                 "提交订单；核对订单金额组成",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/orders/{id} 校验 data.freightAmount 等于 0 且 data.payAmount 等于 data.goodsAmount",
                 "用例结束后调用 POST /api/orders/{id}/cancel 关闭该订单",
                 "接口文档§3.5；需求：MALL-05"))
    r.append(row("BUS-ORD-004", "P1", "业务",
                 "提交订单_购物车金额低于免邮门槛_收取默认运费",
                 pre(USER, "商品金额低于免邮门槛；准备方式：加购金额小于 99 元的专属商品并取默认地址 id"),
                 "POST /api/orders", UH,
                 '{"addressId": 地址 id（先调用 GET /api/addresses 取）, "idempotentKey": "ORD004-唯一键", "cartItemIds": [购物车项 id]}',
                 "提交订单；核对运费与应付金额",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/orders/{id} 校验 data.freightAmount 等于系统配置的默认运费，且 data.payAmount 等于 data.goodsAmount 加运费",
                 "用例结束后调用 POST /api/orders/{id}/cancel 关闭该订单",
                 "接口文档§3.5；需求：MALL-05"))
    r.append(row("BUS-ORD-005", "P0", "业务",
                 "取消订单_已支付订单_提示订单状态不允许",
                 pre(USER, "已支付订单；准备方式：本用例先下单并调用沙箱支付"),
                 "POST /api/orders/{id}/cancel", "Authorization: Bearer <普通用户 token>",
                 "路径参数 id：运行时先调用 GET /api/orders?page=1&size=5 取一条状态为待收货的订单 id",
                 "取消已支付订单；回查订单状态",
                 "200", "ORDER_STATUS_INVALID",
                 "HTTP=200；code=ORDER_STATUS_INVALID；回查 GET /api/orders/{id} 校验状态未变更为 CLOSED",
                 "无需清理（状态未变更，使用专属订单）", "接口文档§3.5；需求：MALL-08"))
    r.append(row("BUS-ORD-006", "P0", "业务",
                 "确认收货_待收货订单_订单完成",
                 pre(USER, "待收货订单；准备方式：本用例先下单、沙箱支付并由管理员发货"),
                 "POST /api/orders/{id}/confirm", "Authorization: Bearer <普通用户 token>",
                 "路径参数 id：运行时先调用 GET /api/orders?page=1&size=5 取一条状态为待收货的订单 id",
                 "确认收货；回查订单详情",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/orders/{id} 校验 data.status 为 COMPLETED",
                 "无需清理（状态类动作不可撤销，使用专属订单）", "接口文档§3.5；需求：MALL-09"))
    r.append(row("BUS-ORD-007", "P0", "业务",
                 "确认收货_待发货订单_提示订单状态不允许",
                 pre(USER, "待发货订单；准备方式：本用例先下单并沙箱支付"),
                 "POST /api/orders/{id}/confirm", "Authorization: Bearer <普通用户 token>",
                 "路径参数 id：运行时先调用 GET /api/orders?page=1&size=5 取一条状态为待发货的订单 id",
                 "对待发货订单确认收货；回查订单状态",
                 "200", "ORDER_STATUS_INVALID",
                 "HTTP=200；code=ORDER_STATUS_INVALID；回查 GET /api/orders/{id} 校验状态仍为 PENDING_SHIP",
                 "无需清理（状态未变更，使用专属订单）", "接口文档§3.5；需求：MALL-09"))
    r.append(row("BUS-ORD-008", "P0", "业务",
                 "查询订单详情_访问他人订单_提示资源不存在",
                 pre(USER, "他人订单 id；准备方式：用另一测试账号下单取得订单 id，本用例以当前账号访问"),
                 "GET /api/orders/{id}", "Authorization: Bearer <普通用户 token>",
                 "路径参数 id：运行时先调用 GET /api/orders?page=1&size=5 取一条归属其他会员的订单 id",
                 "以非归属会员身份查询该订单详情；记录响应",
                 "200", "NOT_FOUND",
                 "HTTP=200；code=NOT_FOUND；响应不含该订单的任何业务字段",
                 "无需清理（只读）", "接口文档§3.5；需求：MALL-07"))
    r.append(row("BUS-PAY-001", "P0", "业务",
                 "沙箱支付_待支付订单_订单转为待发货并写入流水",
                 pre(USER, "待支付订单；准备方式：本用例先提交一笔专属订单"),
                 "POST /api/payment/sandbox", UH,
                 "请求体：{orderId 先调用 GET /api/orders 取一条状态为待支付的订单 id}",
                 "发起沙箱支付；回查订单详情与支付状态",
                 "200", "0",
                 "HTTP=200；code=0；data.status 为 PENDING_SHIP；回查 GET /api/payment/{orderId}/status 校验 data.status 为 PAID 且 tradeNo 非空",
                 "无需清理（状态类动作不可撤销，使用专属订单）", "接口文档§3.6；需求：MALL-06"))
    r.append(row("BUS-PAY-002", "P0", "业务",
                 "沙箱支付_重复支付已支付订单_提示订单状态不允许",
                 pre(USER, "已支付订单；准备方式：本用例先下单并完成一次沙箱支付"),
                 "POST /api/payment/sandbox", UH,
                 "请求体：{orderId 先调用 GET /api/orders 取一条状态为待发货的订单 id}",
                 "对已支付订单再次发起支付；回查支付流水条数",
                 "200", "ORDER_STATUS_INVALID",
                 "HTTP=200；code=ORDER_STATUS_INVALID；回查 GET /api/payment/{orderId}/status 校验仅存在一条支付流水",
                 "无需清理（未产生新流水，使用专属订单）", "接口文档§3.6；需求：MALL-06"))
    r.append(row("BUS-PAY-003", "P1", "业务",
                 "沙箱支付_访问他人订单_提示资源不存在",
                 pre(USER, "他人待支付订单 id；准备方式：用另一测试账号下单取得订单 id"),
                 "POST /api/payment/sandbox", UH,
                 "请求体：{orderId 先调用 GET /api/orders?page=1&size=5 取一条归属其他会员的订单 id}",
                 "以非归属会员身份支付该订单；记录响应",
                 "200", "NOT_FOUND",
                 "HTTP=200；code=NOT_FOUND；回查该订单支付状态未被改变",
                 "无需清理（只读）", "接口文档§3.6；需求：MALL-06"))

    # ---------------- 售后（MALL-10） ----------------
    r.append(row("BUS-AS-001", "P0", "业务",
                 "申请售后_待收货订单_创建待审核售后单",
                 pre(USER, "待收货订单；准备方式：本用例先下单、支付并由管理员发货"),
                 "POST /api/after-sales", UH,
                 '{"orderId": 订单 id（先调用 GET /api/orders 取）, "type": "REFUND", "reason": "自动化测试申请售后"}',
                 "提交售后申请；回查我的售后列表",
                 "200", "0",
                 "HTTP=200；code=0；data 为新建售后单 id；回查 GET /api/after-sales 校验该单 status 为 PENDING_AUDIT",
                 "无需清理（状态类动作不可撤销，使用专属订单）", "接口文档§3.7；需求：MALL-10"))
    r.append(row("BUS-AS-002", "P1", "业务",
                 "申请售后_待支付订单_提示订单状态不允许",
                 pre(USER, "待支付订单；准备方式：本用例先提交一笔未支付的专属订单"),
                 "POST /api/after-sales", UH,
                 '{"orderId": 订单 id（先调用 GET /api/orders 取）, "type": "REFUND", "reason": "自动化测试申请售后"}',
                 "对待支付订单申请售后；回查售后列表",
                 "200", "ORDER_STATUS_INVALID",
                 "HTTP=200；code=ORDER_STATUS_INVALID；回查 GET /api/after-sales 校验未新增售后单",
                 "用例结束后调用 POST /api/orders/{id}/cancel 关闭该订单",
                 "接口文档§3.7；需求：MALL-10"))
    r.append(row("BUS-AS-003", "P1", "业务",
                 "申请售后_同订单重复申请_提示已有进行中售后",
                 pre(USER, "已有进行中售后的订单；准备方式：本用例先申请一次售后，再对同一订单再次申请"),
                 "POST /api/after-sales", UH,
                 '{"orderId": 已有进行中售后的订单 id（先调用 GET /api/after-sales 取）, "type": "REFUND", "reason": "重复申请"}',
                 "对已有进行中售后的订单再次申请；回查售后列表条数",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /api/after-sales 校验该订单的售后单仍只有一条",
                 "用例结束后由管理员审核驳回该售后单，还原订单的售后可申请状态",
                 "接口文档§3.7；需求：MALL-10"))
    r.append(row("BUS-AS-004", "P0", "业务",
                 "管理员审核售后_审核通过_售后完结",
                 pre(ADMIN, "待审核售后单；准备方式：本用例先由会员提交一笔专属售后申请"),
                 "POST /admin/after-sales/{id}/audit", AH,
                 '路径参数 id：运行时先调用 GET /admin/after-sales?page=1&size=5 取一条待审核售后单的 id；'
                 '请求体：{"approved": true, "remark": "自动化测试审核通过"}',
                 "审核通过；回查售后详情",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /admin/after-sales/{id} 校验 data.afterSale.status 为 COMPLETED 且 auditRemark 等于本次意见",
                 "无需清理（状态类动作不可撤销，使用专属售后单）", "接口文档§2.6；需求：MALL-10"))
    r.append(row("BUS-AS-005", "P1", "业务",
                 "管理员审核售后_驳回未填处理意见_提示参数错误",
                 pre(ADMIN, "待审核售后单；准备方式：本用例先由会员提交一笔专属售后申请"),
                 "POST /admin/after-sales/{id}/audit", AH,
                 '路径参数 id：运行时先调用 GET /admin/after-sales?page=1&size=5 取一条待审核售后单的 id；'
                 '请求体：{"approved": false}',
                 "提交驳回但缺少处理意见的审核请求；回查售后状态",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /admin/after-sales/{id} 校验 status 仍为 PENDING_AUDIT",
                 "用例结束后调用审核接口通过该售后单以完成清理",
                 "接口文档§2.6；需求：MALL-10"))
    r.append(row("BUS-AS-006", "P1", "业务",
                 "管理员审核售后_重复审核已完结售后_提示售后状态不允许",
                 pre(ADMIN, "已完结售后单；准备方式：本用例先提交并审核通过一笔专属售后申请"),
                 "POST /admin/after-sales/{id}/audit", AH,
                 '路径参数 id：运行时先调用 GET /admin/after-sales?page=1&size=5 取一条已完结售后单的 id；'
                 '请求体：{"approved": false, "remark": "重复审核"}',
                 "对已完结售后单再次审核；回查售后状态",
                 "200", "AFTER_SALE_STATUS_INVALID",
                 "HTTP=200；code=AFTER_SALE_STATUS_INVALID；回查 GET /admin/after-sales/{id} 校验 status 仍为 COMPLETED",
                 "无需清理（状态未变更，使用专属售后单）", "接口文档§2.6；需求：MALL-10"))

    # ---------------- 评价（MALL-11） ----------------
    r.append(row("BUS-REV-001", "P1", "业务",
                 "发表评价_已完成订单项_评价可见",
                 pre(USER, "已完成订单项；准备方式：本用例先走完下单-支付-发货-确认收货全链路"),
                 "POST /api/reviews", UH,
                 '{"orderItemId": 订单项 id（先调用 GET /api/orders/{id} 取）, "rating": 5, "content": "自动化测试评价"}',
                 "发表评价；回查商品评价列表",
                 "200", "0",
                 "HTTP=200；code=0；data 为新建评价 id；回查 GET /api/products/{spuId}/reviews 校验该评价 status 为 VISIBLE",
                 "用例结束后由管理员隐藏该评价，清理本次评价数据，清理本次评价数据",
                 "接口文档§3.7；需求：MALL-11"))
    r.append(row("BUS-REV-002", "P1", "业务",
                 "发表评价_同订单项重复评价_提示评价已存在",
                 pre(USER, "已评价的订单项；准备方式：本用例先完成一次评价，再对同一订单项再次评价"),
                 "POST /api/reviews", UH,
                 '{"orderItemId": 已评价订单项 id（先调用 GET /api/orders/{id} 取）, "rating": 4, "content": "重复评价"}',
                 "重复评价同一订单项；回查商品评价列表条数",
                 "200", "REVIEW_EXISTS",
                 "HTTP=200；code=REVIEW_EXISTS；回查 GET /api/products/{spuId}/reviews 校验该订单项的评价仅一条",
                 "用例结束后由管理员隐藏该评价，清理本次评价数据",
                 "接口文档§3.7；需求：MALL-11"))
    r.append(row("BUS-REV-003", "P1", "业务",
                 "发表评价_未完成订单_提示订单状态不允许",
                 pre(USER, "待支付订单的订单项；准备方式：本用例先提交一笔订单但不支付"),
                 "POST /api/reviews", UH,
                 '{"orderItemId": 待支付订单的订单项 id（先调用 GET /api/orders/{id} 取）, "rating": 5, "content": "提前评价"}',
                 "对未完成订单的订单项发表评价；回查评价列表",
                 "200", "ORDER_STATUS_INVALID",
                 "HTTP=200；code=ORDER_STATUS_INVALID；回查 GET /api/products/{spuId}/reviews 校验未新增评价",
                 "用例结束后调用 POST /api/orders/{id}/cancel 关闭该订单",
                 "接口文档§3.7；需求：MALL-11"))
    r.append(row("BUS-REV-004", "P1", "业务",
                 "管理员隐藏评价_已发表评价_商品详情不再展示该评价",
                 pre(ADMIN, "已发表且可见的评价；准备方式：本用例先由会员发表一条专属评价"),
                 "PUT /admin/reviews/{id}/status", AH,
                 '路径参数 id：运行时先调用 GET /admin/reviews?page=1&size=5 取一条记录 id；请求体：{"status": "HIDDEN"}',
                 "隐藏评价；回查 C 端商品评价列表",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/products/{spuId}/reviews 校验该评价不再出现在 data 中",
                 "无需清理（评价已隐藏，使用专属评价数据）", "接口文档§2.8；需求：MALL-11"))

    # ---------------- 管理端链路（RBAC / PRD / CUS / CFG） ----------------
    r.append(row("BUS-ADM-001", "P0", "业务",
                 "管理员创建用户_重复用户名_提示已存在",
                 pre(ADMIN, "已存在同名管理员；准备方式：先创建专属账号 admintest01，再重复提交同名"),
                 "POST /admin/users", AH,
                 '{"username": "admintest01", "realName": "自动化测试", "password": "从环境变量 ADMIN_TEST_PASSWORD 读取", "roleIds": [角色 id（先调用 GET /admin/roles/all 取一条记录的 id）]}',
                 "重复创建同名管理员；回查用户列表",
                 "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查 GET /admin/users?page=1&size=20 校验该用户名仅一条记录",
                 "用例结束后调用 DELETE /admin/users/{id} 删除专属账号",
                 "接口文档§2.2；需求：RBAC-01"))
    r.append(row("BUS-ADM-002", "P1", "业务",
                 "管理员删除超管账号_超管账号_提示状态不允许",
                 pre(ADMIN, "超管账号 id；准备方式：先调用 GET /admin/users?page=1&size=20 取用户名 admin 的记录 id"),
                 "DELETE /admin/users/{id}", "Authorization: Bearer <管理员 token>",
                 "路径参数 id：运行时先调用 GET /admin/users?page=1&size=20 取超级管理员账号的 id",
                 "删除超管账号；回查用户列表",
                 "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查 GET /admin/users?page=1&size=20 校验 admin 账号仍存在",
                 "无需清理（只读，删除被拒绝）", "接口文档§2.2；需求：RBAC-01"))
    r.append(row("BUS-ADM-003", "P1", "业务",
                 "管理员重置密码_指定账号_新密码可登录且旧密码失效",
                 pre(ADMIN, "专属测试账号；准备方式：先创建专属账号并确认可用原密码登录"),
                 "PUT /admin/users/{id}/password", AH,
                 '路径参数 id：运行时先调用 GET /admin/users?page=1&size=20 取专属账号 id；请求体：{"password": "从环境变量读取"}',
                 "重置密码；分别用新密码与旧密码调用管理端登录接口",
                 "200", "0",
                 "HTTP=200；code=0；回查 POST /admin/auth/login 校验新密码登录返回 code=0 且旧密码返回 code=LOGIN_FAILED",
                 "用例结束后调用 DELETE /admin/users/{id} 删除专属账号",
                 "接口文档§2.2；需求：RBAC-01"))
    r.append(row("BUS-ADM-004", "P1", "业务",
                 "管理员创建角色_重复角色编码_提示已存在",
                 pre(ADMIN, "已存在同编码角色；准备方式：先创建编码为 AUTOTEST 的专属角色，再重复提交同编码"),
                 "POST /admin/roles", AH,
                 '{"code": "AUTOTEST", "name": "自动化测试角色", "status": "ENABLED"}',
                 "重复创建同编码角色；回查角色列表",
                 "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查 GET /admin/roles?page=1&size=20 校验该编码仅一条记录",
                 "用例结束后调用 DELETE /admin/roles/{id} 删除专属角色",
                 "接口文档§2.2；需求：RBAC-02"))
    r.append(row("BUS-ADM-005", "P1", "业务",
                 "管理员删除类目_存在子类目_提示状态不允许",
                 pre(ADMIN, "含子类目的类目 id；准备方式：先取 GET /admin/categories/tree 中带子节点的类目 id"),
                 "DELETE /admin/categories/{id}", "Authorization: Bearer <管理员 token>",
                 "路径参数 id：运行时先调用 GET /admin/categories?page=1&size=5 取存在子类目的类目 id",
                 "删除含子类目的类目；回查类目树",
                 "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查 GET /admin/categories/tree 校验该类目及其子类目仍存在",
                 "无需清理（只读，删除被拒绝）", "接口文档§2.4；需求：PRD-01"))
    r.append(row("BUS-ADM-006", "P1", "业务",
                 "管理员删除商品_已上架商品_提示状态不允许",
                 pre(ADMIN, "已上架商品 id；准备方式：先取 GET /admin/products?page=1&size=5 中状态为 ON 的商品 id"),
                 "DELETE /admin/products/{id}", "Authorization: Bearer <管理员 token>",
                 "路径参数 id：运行时先调用 GET /admin/products?page=1&size=5 取一条已上架商品的 id",
                 "删除已上架商品；回查商品列表",
                 "200", "CONFLICT",
                 "HTTP=200；code=CONFLICT；回查 GET /admin/products/{id} 校验该商品仍存在",
                 "无需清理（只读，删除被拒绝）", "接口文档§2.4；需求：PRD-04"))
    r.append(row("BUS-ADM-007", "P0", "业务",
                 "管理员上架商品_缺少可售规格_提示参数错误",
                 pre(ADMIN, "无可用 SKU 的专属草稿商品；准备方式：先创建一条草稿商品且不配置可售规格"),
                 "PUT /admin/products/{id}/on", "Authorization: Bearer <管理员 token>",
                 "路径参数 id：运行时先调用 GET /admin/products?page=1&size=5 取一条草稿商品的 id",
                 "对无可售规格的草稿商品执行上架；回查商品状态",
                 "200", "BAD_REQUEST",
                 "HTTP=200；code=BAD_REQUEST；回查 GET /admin/products/{id} 校验 status 仍为 DRAFT",
                 "用例结束后调用 DELETE /admin/products/{id} 删除专属草稿商品",
                 "接口文档§2.4；需求：PRD-04"))
    r.append(row("BUS-ADM-008", "P1", "业务",
                 "管理员设置定时上架_未来时间_商品保持草稿等待定时任务",
                 pre(ADMIN, "专属草稿商品；准备方式：先创建一条含可售规格与价格的草稿商品"),
                 "PUT /admin/products/{id}", AH,
                 '路径参数 id：运行时先调用 GET /admin/products?page=1&size=5 取一条草稿商品的 id；'
                 '请求体：{"scheduleOnAt": "未来时间且晚于当前时刻", "pics": ["对象键"], "skus": [规格列表]}',
                 "提交未来定时上架时间；回查商品详情",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /admin/products/{id} 校验 status 保持 DRAFT 且 scheduleOnAt 等于提交值",
                 "用例结束后调用 PUT /admin/products/{id} 清空定时上架时间并删除专属商品",
                 "接口文档§2.4；需求：PRD-05"))
    r.append(row("BUS-ADM-009", "P1", "业务",
                 "管理员批量下架商品_多商品_全部转为下架",
                 pre(ADMIN, "多个已上架专属商品；准备方式：先创建并上架两条专属商品"),
                 "PUT /admin/products/batch-off", AH,
                 '请求体：{"ids": [商品 id 列表（先调用 GET /admin/products?page=1&size=5 取）]}',
                 "批量下架；逐条回查商品状态",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /admin/products/{id} 校验两条商品 status 均为 OFF",
                 "用例结束后调用 DELETE /admin/products/{id} 删除专属商品",
                 "接口文档§2.4；需求：PRD-04"))
    r.append(row("BUS-ADM-010", "P1", "业务",
                 "管理员冻结会员_正常会员_该会员后续请求被拒绝",
                 pre(ADMIN, "正常状态会员；准备方式：优先使用专属临时会员账号避免影响演示数据"),
                 "PUT /admin/customers/{id}/status", AH,
                 '路径参数 id：运行时先调用 GET /admin/customers?page=1&size=5 取一条正常状态会员的 id；'
                 '请求体：{"status": "FROZEN"}',
                 "冻结会员；以该会员令牌访问会员资料接口",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/me 校验被冻结会员的请求返回 code=CUSTOMER_FROZEN",
                 "用例结束后调用同一接口将该会员状态还原为 NORMAL",
                 "接口文档§2.7；需求：CUS-05"))
    r.append(row("BUS-ADM-011", "P1", "业务",
                 "管理员修改站点品牌_提交新名称_前台读取同步更新",
                 pre(ADMIN, "站点品牌当前值；准备方式：先调用 GET /admin/site/brand 记录原值便于还原"),
                 "PUT /admin/site/brand", AH,
                 '请求体：{"name": "尚品汇自动化测试", "slogan": "选好物，享生活"}',
                 "修改站点品牌；回查 C 端品牌接口",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /api/site/brand 校验 data.name 等于本次提交值",
                 "用例结束后调用 PUT /admin/site/brand 还原原名称与口号",
                 "接口文档§2.8；需求：CFG-01"))
    r.append(row("BUS-ADM-012", "P2", "业务",
                 "管理员更新系统配置_修改免邮门槛_C端下单按新门槛计算运费",
                 pre(ADMIN, "系统配置可写；准备方式：先调用 GET /admin/system-configs?page=1&size=20 记录免邮门槛原值"),
                 "PUT /admin/system-configs/{id}", AH,
                 '路径参数 id：运行时先调用 GET /admin/system-configs?page=1&size=20 取免邮门槛配置的 id；'
                 '请求体：{"configValue": "199"}',
                 "修改免邮门槛；回查配置详情",
                 "200", "0",
                 "HTTP=200；code=0；回查 GET /admin/system-configs?page=1&size=20 校验该配置项 configValue 等于提交值",
                 "用例结束后调用同一接口还原原门槛值",
                 "接口文档§2.8；需求：CFG-03"))

    return r


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()
    out = args.out or args.inventory
    text = args.inventory.read_text(encoding="utf-8")
    business = build()
    ids = [re.match(r"\|\s*(\S+?)\s*\|", line).group(1) for line in business]
    assert len(ids) == len(set(ids)), "BUS 用例 ID 重复"
    merged = (text.rstrip("\n") + "\n\n## 业务链路用例（BUS-*）\n\n"
              "| 用例ID | 优先级 | 类型 | 标题 | 前置条件 | 请求方法/URL | 请求头 | Path/Query/Body | 步骤 | 预期HTTP | 预期业务码 | 断言点 | 清理 | 备注 |\n"
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"
              + "\n".join(business) + "\n")
    out.write_text(merged, encoding="utf-8")
    print(f"已合并业务用例 {len(business)} 条 → {out}")


if __name__ == "__main__":
    main()
