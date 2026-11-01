# 尚品汇企业级自动化测试框架

Java 21 · Cucumber 7.20 · Allure 2.35 · REST Assured 5.5 · Playwright 1.63 · JUnit Platform 1.11

一套代码同时覆盖 **API 契约/业务规则** 与 **浏览器端到端旅程**，面向本地开发、CI 与课程教学。

## 快速开始

```bash
# 1. 启动本地被测环境（幂等，已在跑的服务会跳过）
bash automation/scripts/start-local.sh

# 2. 全量执行并生成 Allure 报告
bash automation/scripts/run-all.sh

# 3. 查看报告（Allure 必须通过 HTTP 打开）
python3 -m http.server 18080 --directory automation/artifacts/allure-report
# 打开 http://127.0.0.1:18080/
```

只跑某一层：

```bash
cd automation
mvn test -Dcucumber.filter.tags="@api"
mvn test -Dcucumber.filter.tags="@ui"
mvn test -Dcucumber.filter.tags="@smoke"
mvn test -Dcucumber.filter.tags="@e2e"
mvn test -Dcucumber.filter.tags="@negative"
```

API 并行（UI 默认串行，避免共享演示数据竞争）：

```bash
mvn test -Dcucumber.filter.tags="@api" \
  -Dcucumber.execution.parallel.enabled=true \
  -Dcucumber.execution.parallel.config.strategy=fixed \
  -Dcucumber.execution.parallel.config.fixed.parallelism=4
```

## 分层结构

```
src/test/java/com/shangpivot/automation
├─ config/TestConfig              环境变量、默认值、运行目录
├─ core/api                       RestClient / ApiResult / SchemaValidator
├─ core/ui                        BrowserSession（context 隔离、trace、截图）
├─ core/allure                    AllureSupport（脱敏、环境、附件）
├─ core/data                      TestData / TestDataRegistry
├─ core/env                       EnvironmentProbe（启动预检）
├─ core/report                    RunSummary（run-summary.json）
├─ api/                           Auth/Catalog/Cart/Address/Order/Payment/Admin
├─ ui/pages/                      Mall*/Admin* Page Objects
├─ steps/                         按领域拆分的 Step Definitions
├─ hooks/Hooks                    场景生命周期、清理、失败留证
└─ context/ScenarioContext        场景共享状态（PicoContainer）
```

Feature 只写业务语言；Steps 负责翻译；API/Page 封装协议与页面细节；Core 管基础设施。

## 测试范围

| 类别 | 场景/用例数 | 覆盖 |
|------|-------------|------|
| API 主回归 | 55 | 认证/商品/购物车/地址/订单/支付/管理端，以及边界、异常、逆向、安全正向用例 |
| API 已知缺陷 | 7 | 畸形输入、并发幂等、`alg=none`、发货单号冲突等，单独隔离复现 |
| UI | 12 | 首页、商品详情、搜索空态、登录、加购、结算、完整购买旅程、订单取消、管理端 |
| 边界值 | 18 | 数量、库存、分页、手机号、验证码、地址上限 |
| 异常 | 8 | 缺字段、未知 SKU、已取消支付、畸形 JSON、错误 Content-Type、并发幂等 |
| 逆向 | 7 | IDOR、token 隔离、批量赋值、金额篡改、状态机、不存在订单 |
| 安全 | 8 | 非法 token、SQLi、CORS、安全响应头、敏感字段、`alg=none` |

## 配置项

支持 `SHANG_` 前缀（推荐）或无前缀环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SHANG_API_BASE_URL` | `http://127.0.0.1:8086` | 后端 API |
| `SHANG_MALL_BASE_URL` | `http://127.0.0.1:5174` | 商城 |
| `SHANG_ADMIN_BASE_URL` | `http://127.0.0.1:5173` | 管理端 |
| `SHANG_UI_HEADLESS` | `true` | 是否无头 |
| `SHANG_UI_VIEWPORT_WIDTH` / `SHANG_UI_VIEWPORT_HEIGHT` | `1280` / `720` | 商城 <768 会切 H5，必须固定 |
| `SHANG_UI_TIMEOUT_MS` | `15000` | Playwright 默认超时 |
| `SHANG_ARTIFACTS_DIR` | `artifacts` | 截图/失败现场/摘要根目录 |
| `SHANG_RUN_ID` | 时间戳 | 单次运行标识 |
| `SHANG_DEMO_SMS_CODE` | `123456` | 演示环境固定验证码 |
| `SHANG_ADMIN_USERNAME` / `SHANG_ADMIN_PASSWORD` | `admin` / `Admin@123` | 管理端账号 |

真实环境请通过 CI Secret 或 `.env` 注入，不要提交到仓库。

## 运行产物

```
automation/artifacts/
├─ allure-report/                  Allure HTML 报告
└─ run-<RUN_ID>/
   ├─ screenshots/<场景>.png       每个 UI 场景一张全页截图
   ├─ failures/                    失败场景的 DOM、控制台、Playwright trace
   └─ run-summary.json             机器可读摘要（总数/通过率/耗时/标签）
```

`target/allure-results` 是 Allure 原始结果；`mvn clean` 会清空，重新执行后需重新生成报告。

## 标签规范

| 标签 | 用途 |
|------|------|
| `@api` / `@ui` | 执行层 |
| `@smoke` | 发布前冒烟 |
| `@regression` | 回归 |
| `@boundary` | 边界值场景 |
| `@exception` | 异常/故障注入场景 |
| `@negative` | 逆向/越权/状态机场景 |
| `@security` | 安全场景 |
| `@known-defect` | 已知缺陷复现，默认从主回归排除 |
| `@e2e` | 跨页面完整旅程 |
| `@API-*` / `@UI-*` | 稳定用例 ID，便于缺陷与禅道关联 |

### 已知缺陷策略

- `mvn test` 默认执行 `not @known-defect`，主回归保持绿色；
- `mvn test -Dcucumber.filter.tags="@known-defect"` 单独复现缺陷，失败即为预期；
- 已知缺陷专项必须全部转绿后才能发布，不能把“隔离”当成“豁免”；
- 当前 7 个已知缺陷见 [`docs/07-边界异常逆向安全测试执行报告.md`](docs/07-边界异常逆向安全测试执行报告.md)。

## 设计要点

- **不假绿**：环境不可达时预检直接失败；清理失败会让原本通过的场景变红。
- **数据隔离**：每个写场景生成唯一手机号，自动注册新会员；登记购物车项/地址/订单并精确清理。
- **失败可诊断**：UI 失败附全页截图、DOM、控制台日志、Playwright trace；API 每个请求/响应都进 Allure 并脱敏 token。
- **契约先行**：首页、商品详情、下单响应使用 JSON Schema 校验。
- **分层断言**：后端规则用 API 验证，UI 只验证用户可见行为；E2E 最后用 API 复核订单状态。

更详细的架构、运行记录与课程材料见 [`docs/`](docs/)。
