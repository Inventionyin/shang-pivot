# 尚品汇（Shang Pivot）需求文档

> 本文档基于源码逆向整理：仓库 `shang-pivot`（企业 Gitee beijing-chuan-sui/shang-pivot，
> 本地副本提交 `9d03ee3`「整理项目文档」，2026-09-27）。
> 整理日期：2026-10-04。

---

## ⚠️ 与另一个项目的区分（重要）

本项目与桌面 `电商自动化测试/web`（元气购 / ProjectKu）是**两个完全独立的电商项目**：

| 维度 | **尚品汇 shang-pivot（本文档）** | 元气购 ProjectKu（web 仓库） |
|------|-------------------------------|------------------------------|
| C 端品牌 | 尚品汇（选好物，享生活） | 元气购 |
| 定位 | 高仿真电商演示：后台供给 → C 端交易 → 智能客服（分版本演进） | 前后端分离电商示例 |
| 后端 | Java 21 · **Spring Boot 4.1.1** · MyBatis-Plus · 模块化单体 | Java · Spring Boot 3 · MyBatis 单体 |
| 数据库 | **PostgreSQL 16 + pgvector**（Flyway V1~V19 迁移） | MySQL 8（init_db.sql 单脚本） |
| 缓存/存储 | Redis 7 · **MinIO**（S3 文件上传） | Redis（无对象存储） |
| 鉴权 | **JWT 双令牌**（access 15 分钟 + refresh 7 天），`clientType` 区分管理端/C 端 | JWT 单令牌（2 小时） |
| C 端登录 | **短信验证码登录** | 账号密码登录 |
| 库存模型 | **三态库存**（物理/锁定/可售）+ 双档预警 + 出入库单 | 单一 stock 字段 |
| 支付 | **沙箱支付**（按系统配置的沙箱开关） | 模拟二维码 + webhook |
| 后端端口 | 单应用 8080（部署后 Nginx 分流 8090 商城 / 8091 管理端） | 8080（context /api） |
| 仓库来源 | 企业 Gitee（beijing-chuan-sui） | GitHub（KeepJoyTod/web） |

---

## 1. 项目概述

尚品汇（工程名 Shang Pivot）是一个高仿真电商全栈演示项目，核心特点是**版本纪律**：
每个版本自包含、时间线单向。

| 版本 | 内容 | 状态 |
|------|------|------|
| V1.0 | 后台管理（供给侧） | **已交付** |
| V2.0 | 商城 C 端（交易闭环） | **已交付** |
| V3.0 | 智能客服（应答/查单/转人工/工单 + 护栏） | 设计就绪，未实现（`shang-customerservice` 仅为骨架模块） |

**Maven 多模块**：

| 模块 | 职责 |
|------|------|
| `shang-server` | 可运行应用（默认端口 8080，聚合各模块） |
| `shang-identity` / `shang-identity-api` | 认证与 RBAC（用户/角色/部门/菜单/权限/字典） |
| `shang-commerce` / `shang-commerce-api` | 商品/库存/订单/支付/售后/评价/内容配置 |
| `shang-customerservice` | 智能客服（V3 骨架） |
| `shang-common` | 公共组件（统一响应 `Result{code,msg,data,traceId}`、错误码等） |

演示数据规模：8 个一级类目 · 8 个品牌 · 32 个 SPU（在售 30 + 草稿 1 + 下架 1）· 47 个 SKU。

---

## 2. 用户角色

| 角色 | 说明 | 入口/鉴权 |
|------|------|-----------|
| 管理端用户（admin_user） | 后台运营人员，RBAC 精确到菜单与按钮权限 | `/admin/auth/login`，管理端前端（本地 5174 / 部署 8091） |
| C 端会员（customer） | 商城买家，**手机号短信验证码登录** | `/api/auth/login`，商城前端（本地 5173 / 部署 8090） |

- 管理端与 C 端登录态通过 JWT 中的 `clientType` 区分，互不通用。
- 双令牌：access 令牌 15 分钟（900s），refresh 令牌 7 天（604800s），支持静默续期。
- 逻辑删除：全表 `deleted` 标记（MyBatis-Plus 逻辑删除），主键为雪花 ID。

---

## 3. 功能需求（V1 后台管理）

### 3.1 RBAC 权限体系

| 编号 | 需求 | 说明 |
|------|------|------|
| RBAC-01 | 管理员账号管理 | 列表、详情、创建、修改、启用/禁用（status）、重置密码、删除 |
| RBAC-02 | 角色 | 角色 CRUD + 角色授权（role_permission 关联表） |
| RBAC-03 | 部门 | 部门树 + CRUD |
| RBAC-04 | 菜单/权限 | 菜单树、按钮级权限（permission），V3 迁移补齐按钮 |
| RBAC-05 | 数据字典 | 字典 + 字典项管理（含按 code 查询字典项） |

### 3.2 商品域（供给）

| 编号 | 需求 | 规则 |
|------|------|------|
| PRD-01 | 类目树 | 类目树查询 + CRUD，品牌可与类目关联（V7 迁移） |
| PRD-02 | 品牌管理 | 品牌 CRUD |
| PRD-03 | 属性管理 | 属性 CRUD（SPU/SKU 规格属性） |
| PRD-04 | SPU/SKU 商品 | 商品列表/详情/创建/编辑/删除；**上架/下架独立接口**（`/{id}/on`、`/{id}/off`）、**批量下架**（`/batch-off`）；多 SKU（V6 迁移：多规格 + 锁定库存 + 属性） |
| PRD-05 | 定时上架 | 商品编辑支持上架方式（V2 增量），配合系统定时任务 |

### 3.3 库存域

| 编号 | 需求 | 规则 |
|------|------|------|
| STK-01 | 三态库存 | 不变式：`physical_stock ≥ locked_stock ≥ 0`；**可售 = 物理 − 锁定** |
| STK-02 | 库存列表与预警 | 双档预警：低库存档（可售 ≤ 阈值）、预警档（可售 ≤ 阈值 × 1.5），SQL 下推过滤 |
| STK-03 | 库存调整 | 手工增减物理库存并写 ADJUST 流水（stock_log）；无仓库记录则先建仓 |
| STK-04 | 仓库管理 | 仓库列表/创建/修改 |
| STK-05 | 出入库单 | 出入库单创建与查询（V11 迁移补菜单） |

### 3.4 交易域（后台侧）

| 编号 | 需求 | 规则 |
|------|------|------|
| ORD-A01 | 订单列表 | 筛选 + **导出**（V2 增强） |
| ORD-A02 | 订单发货 | 见 §4 ORD-C06 |
| ORD-A03 | 售后审核 | 售后列表/详情/审核（audit） |
| ORD-A04 | 评价管理 | 评价列表 + 状态审核（AdminOpsController） |

### 3.5 内容与配置

| 编号 | 需求 | 规则 |
|------|------|------|
| CFG-01 | 站点品牌 | 站点品牌信息读取/修改（site/brand） |
| CFG-02 | 首页运营位 | 首页槽位（home/slots）查询与配置 |
| CFG-03 | 交易配置 | 系统配置 KV（system_configs），如 `freight.free-threshold`（默认 99 元包邮）、`freight.default-amount`（默认运费 8 元） |
| CFG-04 | 文件上传 | MinIO 对象存储：直传（presign 预签名 + confirm 确认）与服务器中转（upload），单个文件 ≤10MB，预签名 600 秒 |

### 3.6 基础设施

| 编号 | 需求 | 规则 |
|------|------|------|
| INF-01 | 工作台看板 | `/admin/dashboard/stats` |
| INF-02 | 操作日志审计 | 全部后台写操作留痕，支持查询/导出/详情 |
| INF-03 | API 文档 | springdoc-openapi + Knife4j 中文界面：`/v3/api-docs`、`/swagger-ui.html`、`/doc.html` |

---

## 4. 功能需求（V2 商城 C 端）

### 4.1 会员认证

| 编号 | 需求 | 规则 |
|------|------|------|
| CUS-01 | 短信登录 | `POST /api/auth/sms/send` 发送验证码 → `POST /api/auth/login`（或 `/api/auth/phone`）提交手机号+验证码；签发双令牌 |
| CUS-02 | 令牌续期 | `POST /api/auth/refresh`（refresh 令牌换新 access） |
| CUS-03 | 退出登录 | `POST /api/auth/logout` |
| CUS-04 | 会员资料 | `GET /api/me` 查询、`PUT /api/me` 修改 |
| CUS-05 | 会员冻结 | 后台可冻结会员（`/admin/customers/{id}/status`），冻结后登录态失效（`requireActiveCustomer` 校验） |

### 4.2 浏览与交易闭环

| 编号 | 需求 | 规则 |
|------|------|------|
| MALL-01 | 首页 | 运营位内容（/api/home）+ 站点品牌（/api/site/brand） |
| MALL-02 | 分类/商品/搜索 | 分类列表、商品列表、商品详情（SPU+SKU）、关键词搜索 |
| MALL-03 | 购物车 | 增/改/删/查 + **清理失效项**（clear-invalid，如库存变化后失效的行） |
| MALL-04 | 收货地址 | 地址 CRUD |
| MALL-05 | 提交订单 | 从购物车/直接购买生成订单：**下单即锁库存**（locked_stock += qty）；金额 = 商品金额 + 运费（满 99 免运费[可配]，否则收默认运费[可配]）；状态 `PENDING_PAY` |
| MALL-06 | 沙箱支付 | `POST /api/payment/sandbox`（orderId）：支付流水置 PAID、订单置 `PENDING_SHIP`；`GET /api/payment/{orderId}/status` 查询 |
| MALL-07 | 我的订单 | 列表 / 详情 / 取消 / **确认收货** / 物流查询 |
| MALL-08 | 取消订单 | **仅 PENDING_PAY 可取消**；取消置 CLOSED（记录 closeReason）并**解锁库存**；超时未支付同样关闭并释放库存 |
| MALL-09 | 确认收货 | **仅 PENDING_RECEIVE 可确认** → COMPLETED |
| MALL-10 | 售后 | 申请（/api/after-sales）、列表、详情 |
| MALL-11 | 评价 | 发表评价（/api/reviews）、查看商品评价（/api/products/{spuId}/reviews）；管理端可审核 |

### 4.3 会员增值

| 编号 | 需求 | 规则 |
|------|------|------|
| ME-01 | 收藏 | 列表 / 添加（按 spuId）/ 取消 |
| ME-02 | 浏览足迹 | 足迹列表 / 清空（/api/history） |
| ME-03 | 个人设置 | SettingsView（资料/头像，文件走 MinIO） |

### 4.4 商城页面清单

首页 Home、分类 Category、促销 Promo、搜索 Search、商品详情 Product、购物车 Cart、
结算 Checkout、支付 Pay、订单列表/详情 Orders/OrderDetail、售后列表/申请/详情
AfterSales/AfterSaleApply/AfterSaleDetail、收藏 Favorites、足迹 History、
登录 Login、个人中心 Profile、设置 Settings。

### 4.5 管理端页面清单

工作台 Dashboard、用户 Users、角色 Roles、部门 Depts、菜单 Menus、字典 Dicts、
类目 Categories、品牌（SiteBrand）、商品 Products/ProductEdit、库存 Inventory、
出入库 InOut、订单 Orders、会员 Customers、评价 Reviews、售后 Aftersales、
首页运营位 HomeSlots、设置 Settings、日志 Logs。

---

## 5. 数据模型（Flyway V1~V19）

核心表（V1 初始化 + V6~V10 演进）：

| 域 | 表 |
|----|----|
| 身份/RBAC | admin_user、role、permission、role_permission、dept、dict、dict_item |
| 商品 | spu、sku、category、brand、attribute |
| 库存 | stock（物理/锁定/可售/预警阈值）、warehouse、stock_log（出入库流水） |
| 交易 | `order`、order_item、payment、shipment（运单）、after_sale |
| 内容 | file_object（MinIO 对象记录）、system_config（KV）、operation_log |
| C 端 | customer（会员）、customer_address、cart、favorite、history、review、notification 等（V8~V10） |

版本演进要点：V4 种子数据（类目/订单演示数据来自 ProjectKu 迁移）、V6 多 SKU+锁定库存、
V7 品牌类目关联、V8 会员购物车地址、V9 SPU/订单改造、V10 评价/首页/站点、V12 沙箱支付配置、
V14 V2 演示数据、V19 清理旧演示类目。

---

## 6. 非功能需求（源码可证）

1. **统一响应**：`Result{code, msg, data, traceId}`；`Result.ok()` / `Result.fail(ErrorCode)`。
2. **逻辑删除** + 雪花 ID；Jackson 时区 Asia/Shanghai；null 字段不输出。
3. **CORS 白名单**：本地 5173/5174/3000/8086。
4. **数据库连接池**：Hikari（max 5）；Flyway `baseline-on-migrate` + `repair-on-migrate`。
5. **配置外置**：`.env` 覆盖（DB/Redis/MinIO/JWT 密钥/端口），JWT 密钥默认值仅限开发。
6. **API 文档**：Knife4j 中文（`/doc.html`）。
7. **质量工具**：Vitest、Cypress（管理端 E2E）、ESLint、oxlint、Prettier。
8. **V3 客服**：仅骨架，不做自训大模型；护栏与工单设计见 `docs/03-ai/`。

---

## 7. 状态字典（自动化测试断言用）

**订单状态 order.status**：
`PENDING_PAY`（待支付，可取消）→ `PENDING_SHIP`（已支付待发货）→ `PENDING_RECEIVE`（已发货，可确认收货）→ `COMPLETED`（已完成）；`CLOSED`（已关闭：用户取消/超时，均解锁库存）

**支付状态 payment.status**：PAID（沙箱支付成功即 PAID）

**库存不变式**：`physical_stock ≥ locked_stock ≥ 0`；可售 = physical − locked

**售后状态**：初始 SUBMITTED（按源码实体字段；审核走 /admin/after-sales/{id}/audit）

**系统配置键**：`freight.free-threshold`（默认 99）、`freight.default-amount`（默认 8）

**错误响应**：`Result{code, msg, data, traceId}`；常见 `ErrorCode`：`NOT_FOUND`（订单不存在）、
`ORDER_STATUS_INVALID`（订单状态不合法）、`BAD_REQUEST`（如"请录入运单号"）

---

## 8. 与元气购（ProjectKu）的测试注意差异

1. 登录方式：尚品汇 C 端是**短信验证码**（先 send 再 login），元气购是账号密码——自动化时短信码需从
   测试通道/日志获取或使用沙箱机制。
2. 令牌管理：尚品汇 access 只有 15 分钟，长流程测试必须实现 refresh 续期；元气购 2 小时。
3. 库存断言：尚品汇可对 `physical/locked/可售` 三值分别断言，取消订单必须验证解锁；
   元气购只有单一 stock。
4. 运费：尚品汇有包邮门槛与默认运费配置（99/8 可改），订单金额断言需读取 system_config；
   元气购无运费模型。
5. 接口文档：尚品汇有 Knife4j 在线文档（`/doc.html`），可直接导出 OpenAPI；
   元气购只有自建的 springdoc 兼容层。
6. 文件上传：尚品汇走 MinIO 预签名两段式（presign→confirm），流程与普通 form 上传不同。
