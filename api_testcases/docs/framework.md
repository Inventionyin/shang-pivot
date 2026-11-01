# 接口自动化框架（pytest + 契约校验 + Allure）

> 本文件描述**实际实现**。`framework_design.md` 是最初的设计蓝图（其中的 schema 校验层当时未实现，本轮补上）。

## 1. 分层结构

```text
api_testcases/
├── test_cases_inventory.md      ← 唯一内容源：474 条用例（424 契约 + 50 业务链路）
├── conftest.py                  ← Allure 环境信息 / categories.json / 分层标签 fixture / 结果落盘
├── core/
│   ├── shang_driver.py          ← 取值约定解析、请求构造、角色登录、幂等与回查驱动
│   ├── assert_engine.py         ← L2 业务语义断言（回查、状态、取值、两次响应一致）
│   ├── schema_validator.py      ← 【本轮新增】L1/L3/L4 契约校验
│   └── api_client.py            ← 通用客户端（重试 + Allure 请求响应附件）
├── schemas/                     ← 【本轮新增】从 OpenAPI 生成的 JSON Schema
│   ├── endpoints.json           ← 135 个端点：响应/请求 schema（$ref 已展开）
│   └── meta.json                ← 指纹、覆盖率、Long 字段规则（26 条）
├── scripts/
│   ├── build_schemas.py         ← 【本轮新增】OpenAPI → schemas/
│   ├── allure_report.sh         ← 【本轮新增】生成/预览 Allure 报告
│   ├── case_lint.py             ← 用例规范门禁（rule.md v2.6）
│   └── release_gate.py          ← C1–C8 投产判据
└── tests/  contract/ · ui/ · smoke/（contract 为清单驱动主体）
```

## 2. 四层断言（对齐 `docs/测试资料/rule.md` §断言分层）

| 层 | 断言什么 | 实现在哪 |
|---|---|---|
| L1 协议 | HTTP 状态码 + 信封 `code` 为整数、`msg` 为字符串、`code=0` 时 `data` 存在（空体端点除外） | `schema_validator.check()` |
| L2 业务 | 业务码、状态流转、字段取值、回查一致、分页边界 | `assert_engine.evaluate()`（原有） |
| L3 数据 | **JSON Schema 结构校验**（131/135 端点有响应 schema）+ **Long 必须序列化为字符串**（26 条规则） | `schema_validator.py`（本轮新增） |
| L4 可观测 | `traceId` 存在、32 位十六进制、与响应头 `X-Trace-Id` 一致 | `schema_validator.py`（本轮新增） |

**为什么 L3 的 Long 规则是独立一条**：全局 Jackson `ToStringSerializer` 把 `Long` 输出为**字符串**
（实测 `id='10'`、`total='8'`、`expiresIn='900'`），而 **OpenAPI 按 Java 类型记成了 `integer/int64`——spec 在这里不准确**。
生成器已把 Long 字段放宽为 `[string, integer]`（结构校验不误报），再单独用 26 条规则严格断言「必须是字符串」。
取到数字即为回归：前端会丢精度，且与 `docs/06-baseline/04-字段映射.md` 的约定冲突。

## 3. 怎么跑

```bash
cd api_testcases

# 1) 目标环境：默认读 config/env/dev.yaml（本地 8086）；TEST_ENV=demo 切部署演示站
TEST_ENV=dev python3 -m pytest tests/contract tests/ui -q --alluredir artifacts/allure

# 2) 生成并查看 Allure 报告（CLI 由 npm 装在 api_testcases/ 下）
bash scripts/allure_report.sh --results artifacts/allure --report artifacts/allure-report
bash scripts/allure_report.sh --serve              # 或直接起服务预览

# 3) 门禁
python3 scripts/case_lint.py        # 用例规范（fail 必须为 0）
python3 scripts/release_gate.py     # C1–C8 投产判据
```

环境变量（均有默认值，见 `config/env/*.yaml`）：

| 变量 | 默认 | 作用 |
|---|---|---|
| `TEST_ENV` | `dev` | 选 `config/env/<值>.yaml`：`dev`=本地 8086，`demo`=部署演示站 |
| `ADMIN_BASE_URL` / `MALL_BASE_URL` | 同上 | 覆盖目标地址 |
| `SCHEMA_CHECK` | `1` | 关掉契约校验层（`0`） |
| `EXECUTE_WRITES` | `0` | **默认跳过所有写操作用例**（见 §5） |
| `INCLUDE_BUS` | `1` | 是否纳入 `BUS-*` 端到端业务链路用例 |

## 4. 本条流水线的实测结果（2026-10-10）

```
EXECUTE_WRITES=0 python3 -m pytest tests/contract tests/ui -q --alluredir artifacts/allure-fixed
→ 3 failed, 132 passed, 340 skipped in 4.06s
```

- **契约校验层贡献 0 失败**，且 132 条通过用例全部带「契约校验通过」步骤（报告可查）。
- 3 条失败**全部在 `artifacts/known-failures.json` 的登记表内**（满足发布门禁 C3「失败 ⊆ 已登记」）：
  `GET_ADMIN_DICT_ITEMS-003`、`GET_ADMIN_DICT_ITEMS_BY_CODE-003`（D-4/D-5：缺必填 query 走 500 而非 400）、
  `BUS-ORD-008`（D-13/D-18）。
- 报告亮点：ENVIRONMENT 显示实际目标地址与开关状态；CATEGORIES 按「回归类型」自动分桶
  （契约结构 / Long 序列化 / 可观测性 / 5xx / 业务规则）；FEATURES BY STORIES 可按资源与用例类型钻取；
  严重级别由优先级映射（P0→blocker）。

## 5. 必须知道的坑（都在本轮实测踩到）

| # | 坑 | 说明 |
|---|---|---|
| 1 | **`EXECUTE_WRITES` 原本声明了但从未使用** | 写用例会真实建库记录，一次全量运行可在 brands/categories/depts/dicts/permissions 各残留约 20 条。本轮补上门控：默认跳过写用例。**这也解释了演示库里累积的测试残留**（本地 brand 46 条、远程 167 条名为「自动化测试名称」）。 |
| 2 | 契约校验必须用**主响应** | cleanup 的 DELETE 会覆盖 `body`、回查会覆盖 `client.last_headers`；用错变量会产出 69 条误报（曾把清理响应当主响应校验）。现已在主调用后立即快照 `main_body/main_http/main_headers`。 |
| 3 | `main_*` 需在 `try` 之前占位 | 用例可能在主调用前 skip，而 `finally` 引用这些变量 → `UnboundLocalError` 会把跳过记成 broken。 |
| 4 | Allure 的 `environment.properties` **按 Latin-1 读** | 写中文会乱码，本轮已改 ASCII（中文说明请用 `environment.xml`）。 |
| 5 | `categories.json` 的 `messageRegex` 要锚定 | `.*traceId.*` 会误命中失败消息里 body 转储的 `traceId` 字样，造成分类虚高；现锚定 `\[L4可观测\]` 层前缀。 |
| 6 | bash 里 `$VAR` 紧跟**全角字符** | `echo "$RESULTS）"` 会被当成变量名 `RESULTS）` → `unbound variable`。已统一写 `${RESULTS}`。 |
| 7 | Allure 需 CLI（Java 实现） | 只装 `allure-pytest` 只能写结果 JSON，出 HTML 报告必须有 `allure-commandline`；已用 npm 本地安装（未写进 package.json 依赖）。 |

## 6. 与既有资产的关系

- 用例内容仍以 `test_cases_inventory.md` 为唯一来源，本轮**未新增/改写任何用例**——只增加了一层断言。
- 真值层级不变：业务预期确认表 > 接口文档/OpenAPI > 后端源码；schema 不符属「源码/契约不一致」，按缺陷处理。
- 跨版本验证仍走 `docs/06-baseline/03-版本验证与自动化.md` 的 diff 门禁，与本框架互补（前者管「版本之间变了什么」，后者管「当前版本对不对」）。
