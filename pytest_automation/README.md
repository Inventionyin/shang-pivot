# pytest_automation — 尚品汇 API + UI 自动化框架

基于 `pytest-learning`（第 5/7/8/10/11/12/14/15/16 集）的写法，为尚品汇搭建的 pytest 自动化框架：
同一套配置、日志、客户端、断言、页面对象与报告，覆盖 **接口** 与 **浏览器 UI** 两类用例。

与仓库内既有资产的关系：

| 套件 | 技术栈 | 定位 |
|---|---|---|
| `api_testcases/` | pytest + 清单驱动 | 478 条禅道用例的契约执行流水线（保留不动） |
| `ui_testcases/` | Java + Cucumber + Playwright | 12 条 Java UI 场景（保留不动） |
| **`pytest_automation/`（本目录）** | **pytest + requests + Playwright** | **可独立运行的 API/UI 业务用例与框架** |

---

## 1. 快速开始

```bash
cd pytest_automation

# 1) 依赖（本机已装；新环境执行）
python3 -m pip install -r requirements.txt
python3 -m playwright install chromium

# 2) 管理端密码：二选一
#    A. 环境变量（CI / 临时运行）
export ADMIN_PASSWORD='运行时输入'
#    B. 本地 .env.local（已 gitignore，VS Code 直接跑也不会 skip）
#       cp .env.example .env.local  然后填入 ADMIN_PASSWORD

# 3) 全量（--clean-alluredir 避免多轮结果叠加）
python3 -m pytest -q --alluredir=artifacts/allure --clean-alluredir

# 4) 生成 HTML 报告
bash scripts/allure_report.sh
# 或直接预览
bash scripts/allure_report.sh --serve
```

只想跑一类：

```bash
python3 -m pytest -m api      # 接口（含 1 条 API 端到端，共 98 条）
python3 -m pytest -m ui       # 浏览器（含 1 条 UI 端到端，共 47 条）
python3 -m pytest -m smoke    # 冒烟（15 条）
python3 -m pytest -m e2e      # 端到端（2 条）
```

### 并行执行（pytest-xdist，两阶段）

```bash
make api-parallel        # 两阶段并行：库存分片 + serial lane + 产物合并，结果与串行一致（推荐）
make api-parallel-publish# 同上，并把并行结果发布到 Allure/接口视图（保留 UI 结果）
make api-fast            # 朴素并行 -n 4：最快但存在共享状态竞争，仅用于本地快速反馈
```

实测（701 条 API + 47 条 UI，12 核）：API 串行 271s → 并行中位数 **84s（3.23x）**，
UI 串行 174s → 并行中位数 **83s（2.10x）**；结果集合与串行完全一致。
API 并行默认 `--dist loadgroup`（每个接口独立并发），`DIST=worksteal` 可换取更快但放弃接口级隔离。
`serial` 标记覆盖批量库存（15/20 SKU）与登录失败限流类用例，第二阶段串行执行；
并行时库存按 worker 分片，脚本会清理 Redis 登录失败计数并合并各 worker 产物。对比结果可在报告站点的
「接口自动化 → 并行对比」子 tab 查看（或 `/benchmark.html`）。详见
[`docs/并行执行对比-xdist.md`](docs/并行执行对比-xdist.md)。

---

## 2. 目录结构

```text
pytest_automation/
├── pytest.ini                  # testpaths / pythonpath / markers / addopts
├── requirements.txt
├── Makefile                    # make api / ui / smoke / full / report / clean
├── conftest.py                 # 插件、Allure 标签、失败截图、results.json
├── config/
│   ├── settings.py             # YAML + 环境变量覆盖
│   └── env/{demo,dev}.yaml
├── core/
│   ├── api_client.py           # Session、超时、GET 重试、日志、Allure 附件
│   ├── assertions.py           # HTTP/业务码/分页/traceId 断言
│   ├── base_page.py            # 导航、networkidle、截图
│   └── logger.py               # 控制台 + artifacts/logs/automation.log
├── helpers/
│   ├── auth_helper.py          # 登录、随机会员、选 SKU、加购、下单
│   └── data_factory.py         # 手机号、地址、幂等键
├── fixtures/
│   ├── api_fixtures.py         # anonymous / admin / customer / sku / address / order
│   └── ui_fixtures.py          # 匿名页面、token 注入的登录态页面
├── pages/                      # 商城 6 个 + 后台 2 个页面对象
├── data/
│   ├── case_meta.yaml          # 用例标题/验证点（用例库的数据源）
│   └── login_validation.yaml   # 数据驱动输入校验
├── scripts/
│   ├── export_cases.py         # 从 pytest 收集结果生成用例库
│   └── allure_report.sh
├── test_cases.md               # 145 条用例清单（人读，自动生成）
├── test_cases.csv              # 145 条用例清单（Excel 可读）
├── test_cases.json             # 145 条用例清单（机读，含 nodeid/marker）
├── tests/
│   ├── api/                    # 11 个文件，97 条
│   ├── ui/                     # 7 个文件，46 条
│   └── e2e/                    # 1 个文件，2 条
└── artifacts/                  # 运行后生成（已 gitignore）
    ├── allure/                 # Allure 结果 JSON
    ├── allure-report/          # HTML 报告
    ├── logs/automation.log
    ├── ui/failures/*.png       # UI 失败截图
    ├── ui/steps/<nodeid>/      # 每步截图 + steps.json
    ├── ui/playwright/<nodeid>/ # 每用例一份 trace.zip
    └── results.json            # 机器可读结果
```

---

## 3. 环境与凭据

### 3.1 环境切换

```bash
TEST_ENV=demo python3 -m pytest   # 默认：部署演示站（60084/60085）
TEST_ENV=dev  python3 -m pytest   # 本地后端 8086 + 演示站前端
```

| 变量 | 默认 | 作用 |
|---|---|---|
| `TEST_ENV` | `demo` | 选择 `config/env/<值>.yaml` |
| `MALL_BASE_URL` / `ADMIN_BASE_URL` | 见 YAML | 覆盖接口地址 |
| `UI_MALL_URL` / `UI_ADMIN_URL` | 见 YAML | 覆盖浏览器地址 |
| `TEST_PHONE` | `13900000009` | 登录类 UI 用例使用的固定演示会员 |
| `TEST_SMS_CODE` | `123456` | 演示环境固定验证码 |
| `ADMIN_USERNAME` | `qa`（demo）/ `admin`（dev） | 管理端用户名 |
| `ADMIN_PASSWORD` | 空 | 环境变量或本地 `.env.local`；两者都没有时管理端用例 skip |
| `UI_TIMEOUT_MS` | `15000` | 元素等待上限 |
| `RUN_UI` | `1` | 设 `0` 跳过 UI 用例 |
| `ARTIFACTS_DIR` | `artifacts` | 产物目录 |

> 凭据不提交到仓库：支持 `pytest_automation/.env.local`（已 gitignore），
> 加载优先级为 **真实环境变量 > `.env.local` > `.env`**。
> 没有本地文件也不会报错，管理端用例会 skip 并给出明确原因。

### 3.2 为什么会话默认打演示站

- 商城/后台前端只在演示站部署，本地没有起前端 dev server。
- 本机实测：本地 8086 的下单接口返回 `50000 系统异常`，演示站下单正常。
- 因此写操作（地址、购物车、订单、支付）默认打 `demo`；本地 `dev` 适合只读排查。

---

## 4. 用例清单

完整清单已落盘，三种格式内容一致：

| 文件 | 用途 |
|---|---|
| [test_cases.md](test_cases.md) | 人读清单：按类型/模块分组，含用例 ID、优先级、标题、验证点、参数、脚本位置 |
| [test_cases.csv](test_cases.csv) | Excel/表格工具直接打开（UTF-8 BOM） |
| [test_cases.json](test_cases.json) | 机读：含 nodeid、marker、参数、脚本位置 |

生成方式（用例增删后重跑，保证条数与实际收集一致）：

```bash
python3 scripts/export_cases.py
```

- 用例标题与验证点维护在 [data/case_meta.yaml](data/case_meta.yaml)。
- 导出脚本直接读取 `pytest --collect-only`，所以 **145 条 = 实际会执行的 145 条**。
- 稳定编号：`API-AUTH-001`、`UI-LOGIN-002`、`E2E-001`（按类型 + 模块 + 序号）。

### 4.1 接口用例（97 条）

| 文件 | 条数 | 覆盖 |
|---|---:|---|
| `test_auth.py` | 13 | 发送验证码、登录契约、错误码、me、刷新、登出失效、改资料、后台登录/鉴权/traceId |
| `test_catalog.py` | 16 | 首页 feed/运营位、品牌、分类、分页、价格排序、详情、40410、搜索命中/空结果/注入/异常分页 |
| `test_cart.py` | 11 | 加购、数量、勾选、删除、失效清理、未登录 401、未知 SKU、数量边界、超库存 |
| `test_address.py` | 7 | 增删改查、默认地址、必填校验、越权改/删 |
| `test_order.py` | 16 | 购物车下单、幂等、立即购买、缺幂等键/非法地址、列表/详情、取消幂等、确认收货、沙箱支付、重复支付、越权读取 |
| `test_favorites_history.py` | 4 | 收藏增删查、足迹记录/清空 |
| `test_after_sales_reviews.py` | 6 | 售后状态机、非法类型、必填、我的售后、评价状态、公开评价列表 |
| `test_admin.py` | 11 | 工作台指标、商品列表/详情、类目树、品牌、字典、订单、会员、操作日志、不存在资源 |
| `test_security.py` | 6 | 篡改 token、商城 token 越权后台、登录注入、搜索 XSS、超大分页、错误响应 traceId |
| `test_data_driven.py` | 4 | YAML 数据驱动：手机号/验证码/clientType 输入校验 |
| `test_smoke.py` | 3 | 商城可达、会员登录、管理员登录 |

### 4.2 UI 用例（46 条）

| 文件 | 条数 | 覆盖 |
|---|---:|---|
| `test_mall_browse.py` | 4 | 首页商品、搜索命中、搜索空态、分类页 |
| `test_mall_product.py` | 2 | 商品详情规格、收藏/取消收藏 |
| `test_mall_account.py` | 8 | 个人中心、设置、售后列表、地址新增/删除、收藏取消、足迹清空、活动位 |
| `test_mall_login.py` | 3 | 验证码登录、错误验证码内联提示、手机号格式内联提示 |
| `test_mall_cart_checkout.py` | 5 | 详情页加购、未勾选拦截、改数量、删除、结算下单 |
| `test_mall_orders.py` | 4 | 订单列表状态、订单详情、页面取消订单、收银台支付 |
| `test_admin_console.py` | 20 | 后台登录/工作台/商品/订单/会员 + 14 个管理页面加载 + 新增商品表单 |

### 4.3 端到端（2 条）

| 用例 | 说明 |
|---|---|
| `test_api_purchase_flow` | API：会员 → 地址 → 加购 → 下单 → 沙箱支付 → 订单待发货 |
| `test_ui_purchase_flow` | UI：登录态 → 详情加购 → 购物车 → 确认订单 → 提交 → 支付成功 |

---

## 5. 框架分层

```text
tests/            只写业务断言与流程
pages/            UI 页面对象（选择器、等待、动作）
fixtures/         测试夹具（会员、管理员、SKU、地址、订单、登录态页面）
helpers/          业务辅助（登录、选 SKU、加购、下单、造数）
core/             ApiClient / 断言 / 日志 / BasePage
config/           环境配置（YAML + 环境变量）
```

设计取舍：

1. **写用例用随机会员**：`customer` 夹具每次调用注册新手机号（演示环境固定验证码），
   用例之间不共享购物车/地址/订单；结束后自动取消待支付订单、删地址、清购物车。
2. **UI 不重复登录**：`logged_in_mall_page` 把 API 登录得到的 token 注入
   `localStorage.shang_mall_access`；后台注入 `shang_admin_token`。
   登录表单本身由 3 条专门用例覆盖。
3. **视口锁 1280×720**：商城 `<768px` 会切 H5 DOM，不能与 Web 混跑。
4. **失败必留证**：`pytest_runtest_makereport` 在失败时截整页图到
   `artifacts/ui/failures/` 并挂到 Allure。
5. **按 marker 自动分层**：`P0/P1/P2` → Allure severity；`api/ui/e2e` → epic/feature。

---

## 6. 报告与产物

```bash
# Allure 结果
python3 -m pytest -q --alluredir=artifacts/allure --clean-alluredir

# HTML（优先使用 api_testcases 下已装的 allure-commandline）
bash scripts/allure_report.sh

# 机器可读结果
cat artifacts/results.json
```

其他产物：

- `artifacts/logs/automation.log`：每个请求的 method/path/status/耗时。
- `artifacts/ui/failures/*.png`：UI 失败整页截图。
- Allure 报告内每个请求都带 Request/Response 附件（Authorization 已脱敏）。

### 6.1 UI 步骤截图与 Playwright trace

每个 UI 用例都会自动留证：

| 产物 | 路径 | 内容 |
|---|---|---|
| 步骤截图 | `artifacts/ui/steps/<nodeid>/NN-<步骤>.png` | 每个页面动作后一张整页截图 |
| 步骤清单 | `artifacts/ui/steps/<nodeid>/steps.json` | 序号、步骤名、截图路径、URL、时间 |
| Playwright trace | `artifacts/ui/playwright/<nodeid>/trace.zip` | 每个 UI 用例一份，含操作快照、网络与源码 |

- 页面对象里用 `with self.step("动作名"):` 包住每个用户动作，截图自动完成。
- Allure 报告里：每个步骤下挂对应截图；用例结束额外挂 `Playwright Trace`。
- 查看 trace：

```bash
npx playwright show-trace artifacts/ui/playwright/<nodeid>/trace.zip
```

> trace 在 `logged_in_*` 夹具里手动开启/停止，因此登录态注入的用例也能录到完整操作。

### 6.2 Chrome 扩展 Playwright Trace Handler 实测结论（2026-10-19）

扩展：[Playwright Trace Handler](https://chromewebstore.google.com/detail/playwright-trace-handler/kfjmeidjifholmgemlekobdipkcbofjo)

结论：**不能直接在 Allure 报告里打开 trace，扩展也不能在当前本地报告场景下完成加载。**

实测过程：

1. Allure 默认附件名为 `Playwright Trace`，生成的下载链接是 `a[download="Playwright Trace"]`。
   扩展只接管 `a[download="trace"]`，所以点击附件只会下载 zip，不会打开 Viewer。
2. 把附件名改成 `trace` 后，扩展确实会打开
   `https://trace.playwright.dev/?trace=http://127.0.0.1:8011/data/attachments/<id>.zip`。
3. 但 Viewer 显示：

   ```text
   Could not load trace from http://127.0.0.1:8011/data/attachments/<id>.zip.
   If your trace is in a local or private network, please grant permission for Local Network Access.
   ```

   Chrome 会阻止 `trace.playwright.dev` 读取本机 `127.0.0.1` 的 trace；扩展加的 CORS 规则不足以绕过
   Local Network Access。
4. Google Chrome 155 品牌版还屏蔽了 `--load-extension`，Web Store 安装需要原生确认弹窗；
   本次验证使用 Playwright Chromium（同内核）加载同一份商店 CRX 完成。

因此推荐的工作流仍然是：

```bash
# 从 Allure 下载 trace.zip，然后本地打开
python3 -m playwright show-trace trace.zip
# 或终端分析
npx pwtrace show trace.zip
```

若一定要“从 Allure 点开”，需要额外满足：附件名改为 `trace`、授予
`trace.playwright.dev` 的 Local Network Access 权限，或把报告和 trace 部署到 HTTPS 公网地址。
即使如此，也是在新的在线 Viewer 标签页打开，而不是嵌入 Allure 页面。

---

## 7. 实跑结果（2026-10-19，测试环境 demo）

```text
$ ADMIN_PASSWORD='***' TEST_ENV=demo python3 -m pytest -q
145 passed in 121.69s（0 skipped）
```

- 接口 97 条、UI 46 条、端到端 2 条。
- UI 证据：117 张步骤截图、47 份 Playwright trace，全部挂进 Allure。
- 所有写操作用独立随机会员，运行后清理地址、购物车、待支付订单。
- 管理端用例在既没有 `ADMIN_PASSWORD` 也没有 `.env.local` 时显示为 skipped，而不是失败。
  在 VS Code 里直接跑管理端用例前，先按“快速开始”方式 B 建好本地 `.env.local`。

---

## 8. 已知限制

- **依赖演示环境**：UI 用例需要 `YOUR_SERVER_IP:60084/60085` 可达；站点不可达会直接失败（不静默跳过）。
- **固定验证码**：演示环境 `123456`；接真实短信网关后需改 `TEST_SMS_CODE` 并调整登录流程。
- **支付为沙箱**：`/api/payment/sandbox` 直接成功；真实支付渠道不在本框架范围。
- **售后正向链路未覆盖**：售后要求订单 `PENDING_RECEIVE/COMPLETED`，需要后台发货衔接；
  当前覆盖的是状态机拒绝路径。若需要，可在 `test_after_sales_reviews.py` 增加
  “会员支付 → 管理员发货 → 会员申请售后” 的跨角色链路。
- **本地 8086 下单异常**：本机实测返回 `50000`，属环境/数据问题，不是测试代码问题。

---

## 9. 新增一条用例

**接口用例**（以优惠券为例）：

1. 在 `tests/api/test_xxx.py` 里写测试函数，使用 `customer` / `anonymous` / `admin` 夹具。
2. 用 `core.assertions.ok/error` 断言业务码；写操作结束后自行清理，或复用 `customer` 的自动清理。
3. 加 `@pytest.mark.api` 与 `@pytest.mark.P0/P1/P2`。

**UI 用例**：

1. 在 `pages/` 加页面对象（选择器集中在这里，测试里不写 CSS）。
2. 在 `tests/ui/` 写流程，需要登录态就用 `logged_in_mall_page`。
3. 等待一律用元素级 `wait_for`，不要 `sleep`。

**同步用例库**：

1. 在 `data/case_meta.yaml` 的 `cases` 下补一条 `文件::函数` 元数据（标题、验证点）。
2. 运行 `python3 scripts/export_cases.py`，重新生成 `test_cases.md/csv/json`。
3. 若新增了测试文件，同时在 `modules` 下登记模块名与编号前缀。
