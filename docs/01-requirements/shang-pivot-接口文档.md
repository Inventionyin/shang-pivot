# 尚品汇（Shang Pivot）接口文档

> 基于后端源码逆向整理（23 个 Controller：管理端 17 个 + C 端 3 个 + 文件 2 个 + 配置 1 个）。
> 本文档仅覆盖 **shang-pivot（尚品汇）**，与元气购（ProjectKu）接口完全无关。
> 整理日期：2026-10-04。本地副本提交 `9d03ee3`。

---

## 1. 通用约定

### 1.1 基础地址

| 环境 | 地址 |
|------|------|
| 本地开发 | `http://localhost:8080`（Spring Boot 默认端口，管理端与 C 端同一应用） |
| 部署环境 | 管理端 `http://YOUR_SERVER_IP:60085/`（Nginx → 8091）、商城 C 端 `http://YOUR_SERVER_IP:60084/`（Nginx → 8090） |

- 管理端接口前缀：`/admin/**`
- 商城 C 端接口前缀：`/api/**`

### 1.2 认证（JWT 双令牌）

| 项 | 管理端 | C 端会员 |
|----|--------|----------|
| 登录 | `POST /admin/auth/login`（账号密码） | `POST /api/auth/login`（手机号+短信验证码） |
| 令牌 | access（15 分钟）+ refresh（7 天） | 同左 |
| 区分 | JWT `clientType` 字段区分管理端/C 端，互不通用 | 同左 |
| 续期 | `POST /admin/auth/refresh` | `POST /api/auth/refresh` |
| 请求头 | `Authorization: Bearer <access token>` | 同左 |

### 1.3 统一响应结构

```json
{ "code": 0, "msg": "ok", "data": { ... }, "traceId": "..." }
```

- 成功 `Result.ok(data)`；失败 `Result.fail(ErrorCode)`，常见错误码：
  `NOT_FOUND`（订单不存在）、`ORDER_STATUS_INVALID`（订单状态不合法）、`BAD_REQUEST`（参数缺失，如"请录入运单号"）。
- 分页：MyBatis-Plus 分页（列表接口通用 `page`/`size` 参数）。
- 逻辑删除：删除接口均为逻辑删（`deleted` 标记）。

### 1.4 在线文档

`GET /v3/api-docs`（OpenAPI JSON）、`GET /swagger-ui.html`、`GET /doc.html`（Knife4j 中文）。

---

## 2. 管理端接口

### 2.1 认证 `/admin/auth`（免登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/auth/login` | 管理员登录（账号+密码），返回 access/refresh 双令牌 |
| POST | `/admin/auth/logout` | 退出登录 |
| POST | `/admin/auth/refresh` | 刷新 access 令牌 |
| GET | `/admin/auth/me` | 当前管理员信息 |

### 2.2 RBAC

**用户 `/admin/users`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/users` | 管理员列表（分页） |
| GET | `/admin/users/{id}` | 详情 |
| POST | `/admin/users` | 创建 |
| PUT | `/admin/users/{id}` | 修改 |
| PUT | `/admin/users/{id}/status` | 启用/禁用 |
| PUT | `/admin/users/{id}/password` | 重置密码 |
| DELETE | `/admin/users/{id}` | 删除（逻辑删） |

**角色 `/admin/roles`**：列表 + CRUD + 角色授权。
**部门 `/admin/depts`**：`GET /tree`（部门树）、列表、CRUD。
**菜单/权限 `/admin/permissions`**：`GET /tree`（菜单树）、列表、CRUD（按钮级权限）。
**数据字典**：`/admin/dicts`（CRUD）、`/admin/dict-items`（CRUD）、`GET /admin/dict-items/by-code`（按编码取字典项）。

### 2.3 工作台 `/admin/dashboard`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/dashboard/stats` | 工作台统计看板 |

### 2.4 商品域

**类目 `/admin/categories`**：`GET /tree`（类目树）、列表、POST 创建、PUT 修改、DELETE 删除。
**品牌 `/admin/brands`**：列表、CRUD（品牌可与类目关联）。
**属性 `/admin/attributes`**：列表、CRUD（SPU/SKU 规格属性）。

**商品 `/admin/products`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/products` | 商品列表（分页，含草稿/下架状态） |
| GET | `/admin/products/{id}` | 商品详情（SPU + SKU 列表） |
| POST | `/admin/products` | 创建商品 |
| PUT | `/admin/products/{id}` | 修改商品（含上架方式） |
| PUT | `/admin/products/{id}/on` | 上架 |
| PUT | `/admin/products/{id}/off` | 下架 |
| PUT | `/admin/products/batch-off` | **批量下架** |
| DELETE | `/admin/products/{id}` | 删除 |

### 2.5 库存域

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/stocks` | 库存列表（三态：物理/锁定/可售；支持预警档筛选：低库存 ≤ 阈值、预警 ≤ 阈值×1.5） |
| PUT | `/admin/stocks/adjust` | 手工调整物理库存（写 ADJUST 流水；不变式 physical ≥ locked ≥ 0） |
| GET/POST | `/admin/warehouses`、`PUT /admin/warehouses/{id}` | 仓库列表/创建/修改 |
| GET/POST | `/admin/in-out`（AdminOpsController） | 出入库单查询/创建 |

### 2.6 交易域（后台侧）

**订单 `/admin/orders`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/orders` | 订单列表（筛选 + 分页，V2 增强） |
| GET | `/admin/orders/export` | 订单导出 |
| GET | `/admin/orders/{id}` | 订单详情 |
| POST | `/admin/orders/{id}/ship` | 发货：`{ "trackingNo": "..." }`（**必填运单号**，否则 BAD_REQUEST）；状态 PENDING_SHIP → PENDING_RECEIVE，写 shipment 运单 |

**售后 `/admin/after-sales`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/after-sales` | 售后列表 |
| GET | `/admin/after-sales/{id}` | 售后详情 |
| POST | `/admin/after-sales/{id}/audit` | 售后审核 |

### 2.7 会员管理 `/admin/customers`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/customers` | 会员列表 |
| GET | `/admin/customers/{id}` | 会员详情 |
| PUT | `/admin/customers/{id}/status` | 冻结/解冻会员 |

### 2.8 内容与配置

| 方法 | 路径 | 说明（AdminOpsController） |
|------|------|-----------------------------|
| GET / PUT | `/admin/site/brand` | 站点品牌读取/修改 |
| GET | `/admin/home/slots`、`GET / PUT /admin/home/slots/{code}` | 首页运营位槽位配置 |
| GET | `/admin/reviews`、`PUT /admin/reviews/{id}/status` | 评价管理与审核 |
| GET / POST | `/admin/in-out` | 出入库单 |

**系统配置 `/admin/system-configs`**（SystemConfigController）：交易配置 KV，
如 `freight.free-threshold`、`freight.default-amount`。

### 2.9 文件 `/admin/files`（MinIO）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/files/view` | 文件查看 |
| POST | `/admin/files/upload` | 服务器中转上传（≤10MB） |
| POST | `/admin/files/presign` | 预签名直传（有效期 600 秒） |
| POST | `/admin/files/confirm` | 直传完成确认 |

### 2.10 操作日志 `/admin/operation-logs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/operation-logs` | 日志查询（分页） |
| GET | `/admin/operation-logs/export` | 导出 |
| GET | `/admin/operation-logs/{id}` | 日志详情 |

---

## 3. 商城 C 端接口 `/api/**`

### 3.1 会员认证（免登录部分）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/sms/send` | 发送短信验证码 |
| POST | `/api/auth/login`（别名 `/api/auth/phone`） | 手机号 + 验证码登录，返回双令牌 |
| POST | `/api/auth/refresh` | 刷新 access 令牌 |
| POST | `/api/auth/logout` | 退出登录 |

### 3.2 会员中心（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/me` | 当前会员信息 |
| PUT | `/api/me` | 修改会员资料 |
| GET / POST / PUT / DELETE | `/api/addresses`、`/api/addresses/{id}` | 收货地址 CRUD |
| GET / POST / DELETE | `/api/favorites`、`/api/favorites/{spuId}` | 收藏列表 / 添加 / 取消 |
| GET / DELETE | `/api/history` | 浏览足迹 / 清空足迹 |
| GET | `/api/files/view`、POST `/api/files/upload`、`/api/files/presign`、`/api/files/confirm` | C 端文件（MinIO，同管理端两段式） |

### 3.3 浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/site/brand` | 站点品牌信息 |
| GET | `/api/home` | 首页（运营位槽位 + 推荐内容） |
| GET | `/api/categories` | 分类列表 |
| GET | `/api/products` | 商品列表 |
| GET | `/api/products/{spuId}` | 商品详情（SPU + SKU） |
| GET | `/api/search` | 关键词搜索 |
| GET | `/api/products/{spuId}/reviews` | 商品评价列表 |

### 3.4 购物车（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/cart` | 购物车列表 |
| POST | `/api/cart/items` | 加入购物车 |
| PUT | `/api/cart/items/{id}` | 修改数量 |
| DELETE | `/api/cart/items/{id}` | 删除 |
| POST | `/api/cart/clear-invalid` | 清理失效项（库存变化导致的失效行） |

### 3.5 订单（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders` | 提交订单：下单即**锁库存**；金额 = 商品金额 + 运费（≥ 免邮门槛[默认 99，可配]免运费，否则默认运费[默认 8，可配]）；状态 PENDING_PAY |
| GET | `/api/orders` | 我的订单列表 |
| GET | `/api/orders/{id}` | 订单详情 |
| POST | `/api/orders/{id}/cancel` | 取消订单（仅 PENDING_PAY）：置 CLOSED + 解锁库存 |
| POST | `/api/orders/{id}/confirm` | 确认收货（仅 PENDING_RECEIVE）：置 COMPLETED |
| GET | `/api/orders/{id}/logistics` | 物流查询（运单信息） |

### 3.6 支付（需登录，沙箱）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/payment/sandbox` | 沙箱支付：`{ "orderId": ... }` → 支付流水 PAID、订单 PENDING_PAY → PENDING_SHIP |
| GET | `/api/payment/{orderId}/status` | 支付状态查询（paidAt 等） |

### 3.7 售后与评价（需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/after-sales` | 申请售后 |
| GET | `/api/after-sales` | 我的售后列表 |
| GET | `/api/after-sales/{id}` | 售后详情 |
| POST | `/api/reviews` | 发表评价 |

---

## 4. 状态字典（自动化测试断言用）

**订单状态 order.status**：
`PENDING_PAY`（待支付）→ `PENDING_SHIP`（已支付待发货）→ `PENDING_RECEIVE`（已发货）→ `COMPLETED`（已完成）；
`CLOSED`（已关闭：取消/超时，伴随库存解锁与 closeReason）

**支付状态 payment.status**：PAID（沙箱支付即成功）

**库存不变式**：`physical_stock ≥ locked_stock ≥ 0`，可售 = physical − locked

**金额规则**：`payAmount = goodsAmount + freight`；
`freight = goodsAmount ≥ freight.free-threshold ? 0 : freight.default-amount`（默认 99 / 8，可配）

**令牌有效期**：access 900 秒 / refresh 604800 秒

---

## 5. 与元气购（ProjectKu）接口的主要差异（防混用提醒）

| 差异点 | 尚品汇（本文档） | 元气购（ProjectKu） |
|--------|------------------|---------------------|
| 响应信封 | `Result{code, msg, data, traceId}` | `{code, message, data}` / 认证类 `{data, meta}` |
| C 端登录 | 短信验证码（两步：send → login） | 账号密码 |
| 令牌 | 双令牌 15 分钟 + 7 天，需自动续期 | 单令牌 2 小时 |
| 前缀 | 管理端 `/admin/**`、C 端 `/api/**` | 统一 `/v1/**` |
| 库存 | 三态 + 锁定/解锁 | 单一 stock 扣减 |
| 支付 | 沙箱（同步改状态） | mock 二维码 + webhook 回调 |
| 订单取消 | 仅 PENDING_PAY，置 CLOSED 并解锁 | 仅待支付(0)，置 4 |
| 文件上传 | MinIO presign 两段式 | 无文件上传接口 |
