# 发现记录：前端 DOM 与选择器（供 UI 自动化使用）

> 来源：2026-10-08 对 `shang-mall/` 与 `shang-admin-web/` 源码的逐文件排查（只读）。逐字文案与选择器均带出处行号者见探查报告原文。
> 结论已按"能不能直接写进代码"整理。

## 一、必须先知道的 5 件事

1. **商城 Web/H5 双形态**：`useClientType.ts:19` 以 `width >= 768` 判定；Web 形态无 `van-tabbar`。
   → 测试锁 `viewport = 1280×720`，走 Web 形态。
2. **登录表单是原生 `<input>`**，不是 `van-field`（商城 `LoginForm.vue:109-135`；管理端 `LoginView.vue:79-97`）。
   → 用 `getByPlaceholder()` 直接命中，无组件穿透问题。
3. **全仓无 `data-testid`**（grep 0 命中）→ 只能靠 placeholder / 逐字文案 / 语义化 class。
4. **无任何 Playwright 配置**；`shang-admin-web` 有脚手架 Cypress（未写业务用例）→ 新起 Playwright 不冲突。
5. **部署环境前后端同源**，axios `baseURL='/'`。

## 二、商城（60084）关键选择器

| 目标 | 选择器 |
|---|---|
| 手机号输入 | `getByPlaceholder('请输入 11 位手机号')` |
| 验证码输入 | `getByPlaceholder('请输入 6 位验证码')` |
| 获取验证码 | `locator('button.code-btn')`（倒计时文案变 `59s 后重发`） |
| 登录（独立页） | `locator('button.submit')` |
| 登录（弹层） | `.van-popup` 内 `.pop-foot .btn-primary` |
| 首页商品卡 | `.web-feed-grid .product-card`（href=`/product/{spuId}`） |
| 商品卡标题/价格 | `.product-card__title` / `.product-card__meta .price` |
| 详情页加购/购买 | `.buy-actions .btn-secondary`（加购）/ `.btn-primary`（立即购买）——**只开弹层** |
| 规格 chip | `.pop-panel .sku-row .chip:not(.is-out)`（文案带 ` · ¥xx` / ` · 缺货` 后缀，用正则匹配） |
| 数量加减 | `getByLabel('增加数量')` / `getByLabel('减少数量')` |
| 弹层加购/购买 | `.pop-panel .pop-foot .btn-secondary` / `.btn-primary` |
| 加购成功弹层 | `.pop-title` 逐字 `已加入购物车` |
| 购物车行 | `.cart-item`（`.title` / `.price` / `.link`=删除） |
| 结算按钮 | `.footer .btn-primary` 逐字 `去结算` |
| 购物车合计 | `.footer .sum .price` |
| 结算页应付 | `.kv.total .v.num` |
| 提交订单 | `getByRole('button', { name: '提交订单' })` |
| 确认提交 | `.pop-panel` 含 `确认提交订单` → `.pop-foot .btn-primary`（逐字 `确认提交`） |
| 订单卡 | `.order-card`（`.status` 为中文态） |
| 订单详情状态 | **英文枚举**（`状态 PENDING_PAY`）——与列表页中文不一致，易踩 |

- 商城 token key：`shang_mall_access`（`utils/request.ts:16`）
- 路由守卫：未登录直连 `/checkout`、`/orders` 会 302 到 `/login?redirect=...`；`/`、`/cart`、`/product/*` 放行。
- `/pay/:orderId` **进入即自动发起沙箱支付**（约 900ms 延迟），不是点击触发。

## 三、管理端（60085）关键选择器

| 目标 | 选择器 |
|---|---|
| 用户名 / 密码 | `getByPlaceholder('请输入用户名')` / `getByPlaceholder('请输入密码')`（均有 `name` 属性） |
| 登录按钮 | `locator('button.auth-btn')`（文案是 `登 录`，**中间有空格**） |
| 落地判断 | URL `/dashboard` + `.crumbs` 含 `工作台` + `.brand-name` 逐字 `Shang 后台` |
| 侧栏菜单项 | `.nav-item`（含文本） / 分组 `.nav-group-title` |
| 商品列表 URL | `/products`（工具栏搜索占位符 `商品名称 / 编码`） |
| 表格 | `table.table`（表头：商品图/商品名称/类目/价格区间/SKU 数/可售库存/状态/操作） |
| 状态徽标 | `.badge.badge-forest`(上架) / `badge-brick`(下架) / `badge-amber`(草稿) |
| 分页 | `.pager`（文案 `共 N 条 · 每页 M 条`，翻页按钮是 `‹` `›`，无页码点击） |

- 管理端 token key：`shang_admin_token`；用户信息 `shang_admin_user`。
- ⚠️ 若注入 token 但不预置 `shang_admin_user`，`permissions` 为空会导致侧栏菜单**一个都不渲染**。

## 四、不确定项（需实跑确认）

- 所有 Vant 内部类名（`.van-popup` / `.van-toast` / `.van-checkbox` / `.van-stepper` / `.van-dialog`）：项目**未安装 node_modules**，无法核对 CSS，按 Vant 4 约定推测。
- Vant 命令式 `showConfirmDialog` 的按钮文案（预计 `确认`/`取消`）。
- Element Plus `.el-message` 类名（admin 只用 `ElMessage`，风险低）。
- 商城首页频道栏文案来自后台运营位，**不可硬断言**。
