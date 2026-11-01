# 尚品汇 UI 自动化（Java + Cucumber + Playwright）

> 商城 C 端与管理端的端到端 UI 测试。与 `api_testcases/`（Python 接口自动化）并存，互不依赖。
> 技术栈：Java 21 · Cucumber 8.0.4（JVM）· Playwright 1.63 · JUnit Platform 6.1.3。

## 1. 快速开始

```bash
cd ui_testcases

# 首次：装 Chromium（Playwright Java 自带驱动，浏览器独立下载，约 150MB）
mvn exec:java -Dexec.args="install chromium"

# 跑全部场景
mvn test

# 只跑一个场景（按稳定 ID 过滤）
mvn test -Dcucumber.filter.tags="@UI-MALL-ORDER-001"
```

产物（默认在 `ui_testcases/artifacts/`）：

| 路径 | 内容 |
|---|---|
| `ui/cucumber-report.html` | Cucumber 报告，**每个场景内嵌一张执行截图（成功也截）** |
| `ui/cucumber-report.json` | 机器可读报告 |
| `ui/<runId>/screenshots/<场景>.png` | 每场景一张视口截图（成功/失败都留） |
| `ui/<runId>/failures/<场景>.{png,html,zip}` | 仅失败：整页截图 + 页面 DOM + Playwright trace |
| `ui/<runId>/run.json` | 该轮摘要（跑了哪些场景、什么结果） |
| `automation_results_ui.json` | 结果文件，字段与 `api_testcases/artifacts/automation_results.json` 同构 |

截图策略：**每个场景都截一张挂进报告**（成功标「执行截图」、失败标「失败截图」），失败时额外留
整页截图 + 页面 DOM + trace。trace 只录截图不录 DOM 快照——快照会在每个动作后序列化整页，
本机负载高时能把导航拖到超时；失败的 DOM 由整页截图 + HTML 兜底。

看 trace：`npx playwright show-trace artifacts/ui/<runId>/<场景名>.zip`（需 Node）。

## 2. 配置（全走环境变量，凭据不落文件）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SHANG_MALL_URL` | `http://111.170.33.2:60084` | 商城地址（也认 `MALL_BASE_URL`） |
| `SHANG_ADMIN_URL` | `http://111.170.33.2:60085` | 管理端地址（也认 `ADMIN_BASE_URL`） |
| `APP_TEST_ACCOUNT` | `13800000001` | 演示会员手机号 |
| `APP_TEST_SMS_CODE` | `123456` | 演示环境固定验证码 |
| `ADMIN_TEST_ACCOUNT` / `ADMIN_TEST_PASSWORD` | `admin` / `Admin@123` | 管理端账号 |
| `UI_HEADLESS` | `true` | 设 `false` 可看着浏览器跑 |
| `UI_SLOWMO_MS` | `0` | 放慢动作，便于人工观察 |
| `UI_TIMEOUT_MS` | `15000` | 元素断言等待上限（导航超时另有下限 30s，即 Playwright 默认值） |
| `UI_ARTIFACTS_DIR` | `artifacts/ui` | 留证目录 |
| `RUN_ID` | 时间戳 | 结果文件与本轮的绑定标识 |

**环境自检**：套件启动前会 GET 两个站点，不可达直接失败（**不静默跳过**——否则"站点挂了"会显示成全绿）。

## 3. 场景清单（12 条，稳定 ID 即禅道用例号）

| 稳定 ID | 场景 | 覆盖点 |
|---|---|---|
| `UI-ENV-001` | 商城首页可访问 | 站点存活 |
| `UI-ENV-002` | 管理端登录页可访问 | 站点存活 |
| `UI-MALL-LOGIN-001` | 演示会员用固定验证码登录成功 | 获取验证码 → 登录 → 顶栏登录态 |
| `UI-MALL-LOGIN-002` | 验证码错误时给出明确提示 | 行内错误（非 toast） |
| `UI-MALL-LOGIN-003` | 手机号格式不合法时行内报错 | 前端校验 |
| `UI-MALL-CART-001` | 首页商品加入购物车成功 | 首页→详情→规格弹层→加购成功弹层→购物车 |
| `UI-MALL-CART-002` | 未勾选商品去结算时被拦住 | 负向提示 + 不跳转 |
| `UI-MALL-CART-003` | 勾选后进入确认订单页并可复核金额 | 只带勾选那一件、地址已选、应付金额格式 |
| `UI-MALL-ORDER-001` | 取消待支付订单后状态变为已关闭 | Vant 确认弹窗 → 状态流转 → 列表状态回显 |
| `UI-ADMIN-LOGIN-001` | 管理员登录后进入工作台 | 登录 → 落地页 → 侧栏 |
| `UI-ADMIN-PRODUCT-001` | 商品列表有数据且分页可用 | 侧栏导航 → 表格 → 分页 |
| `UI-ADMIN-PRODUCT-002` | 按关键字查询商品 | 搜索确实生效（见 §6 假绿教训） |

## 4. 目录结构

```
ui_testcases/
├── pom.xml
└── src/test/
    ├── java/com/shangpivot/ui/
    │   ├── RunCucumberTest.java          # JUnit Platform Suite 入口
    │   ├── config/Env.java               # 环境变量 + 启动自检
    │   ├── support/
    │   │   ├── BrowserSession.java       # 浏览器/上下文/trace 生命周期
    │   │   ├── Artifacts.java            # 留证（截图/HTML/trace）
    │   │   ├── ApiClient.java            # 只用于造数/清理与取 token
    │   │   ├── ScenarioContext.java      # 场景状态 + 造数登记 + 清理
    │   │   ├── Hooks.java                # @Before/@After（自检、清理、留证）
    │   │   └── UiResultsPlugin.java      # 结果文件（可交给 zentao_sync 回写）
    │   ├── pages/                        # 6 个页面对象
    │   └── steps/CommonSteps.java        # 步骤定义（中文步骤文本）
    └── resources/
        ├── features/*.feature            # 5 个特性文件
        └── junit-platform.properties     # glue / 报告 / 结果插件配置
```

## 5. 设计约定

- **视口锁 1280×720**：商城 `<768px` 会切到 H5 形态，整套 DOM 不同（`useClientType.ts:19`）。要测 H5 就另起一套并显式改视口，不要混。
- **界面测试与接口造数分工**：被测行为（登录、加购、结算、状态流转）全部走界面；**前置数据**（造一笔待支付订单、准备购物车勾选态）与**收尾清理**走接口。
- **造数必登记、收尾必清理**：`ScenarioContext` 记录本次商品名/订单号，`Hooks` 在 `@After` 里按标题清扫购物车行、取消订单；**清理失败且场景通过 → 场景判红**（不允许"跑完留一堆测试数据"算通过）。
- **成功也留证**：每场景一张截图进报告（回归时能直接看到"当时的页面长什么样"）；失败再加整页截图 + HTML + trace，成功后删除 trace 不占空间。
- **超时不跟着元素等待走**：元素断言 15s、导航 30s（Playwright 框架默认值，不低于它）——
  远程 SPA 的懒加载路由 chunk 未到时根节点是空的，等待过短会把"加载慢"误报成"功能坏"。
- **不静默跳过**：站点不可达、结果为空都直接失败（对齐 `api_testcases` 的"防假绿"纪律）。
- **无 data-testid 的规避**：全站无测试专用属性，选择器以语义 class（`.cart-item` / `.nav-item` / `.pop-panel`）+ 逐字文案为主，避免依赖运行时生成的哈希 class。

## 6. 写用例时的三条坑（都是实跑踩出来的）

1. **Gherkin 标签必须写在 `场景:` 之前**：写在场景名之后会解析失败（报"expected #TagLine…"）。
2. **不要用中文关键字（`功能:` / `假如`）**：Cucumber 8 在 Java 21 上会因 `Locale.Builder.setLanguage("zh-CN")` 抛
   `IllformedLocaleException`（gherkin 无裸 `zh` 方言）。本项目统一用英文关键字 + 中文正文。
3. **别让断言恰好成立**：`UI-ADMIN-PRODUCT-002` 最初用关键字「露营」，而列表第一行正是"折叠露营桌"——
   搜索即使完全失效也会通过（假绿）。换成「帐篷」（默认第 3 行）才真正验证了"过滤生效"。
   写断言时问一句：**"如果被测功能坏了，这条还会通过吗？"**

## 7. 与禅道的关系（已实测）

结果文件字段与接口套件同构，可直接喂给既有回写器：

```bash
export ZENTAO_BASE_URL=... ZENTAO_USER=... ZENTAO_PASSWORD=... ZENTAO_PRODUCT_ID=2
python3 ../api_testcases/scripts/zentao_sync.py sync-results --results artifacts/automation_results_ui.json
```

实测（2026-10-08）：回写器能正确解析出 12 个稳定 ID 并返回
`{"synced": [], "missing_case_ids": [12 个 UI-* ID]}` —— **格式兼容，但禅道里还没有这些 UI 用例**，
所以没有写入任何结果。要让 UI 结果进禅道，下一步得先把这 12 条按 `api_testcases` 的方式建档入库
（清单 → lint → 上传 → 在线核对），目前未做。

## 8. 尚未覆盖（下一批候选）

- 商城：支付页 `/pay/{orderId}`（进入即自动沙箱支付，会留下一笔待发货订单 → 需要专用环境或可回滚的支付开关）、
  售后申请、评价、收藏、地址管理（该账号有 20 条遗留测试地址，需先清理）。
- 管理端：库存调整、订单发货、售后审核、RBAC 页面。
- H5 形态（`<768px`）独立一套。
- CI：目前需人工触发 `mvn test`；接入夜间跑批后应把结果并入门禁 C1–C8。
