# Findings: 尚品汇流水线复用

## 1. 必读三文件的硬约束（已摘录）

### rule.md v2.6
- 14 列清单是唯一内容源；步骤区是「请求方法/请求头/参数」的程序化派生视图（R24 强校验，禁止手工漂移）。
- 标题三段式 `[模块/功能]_[场景/条件]_[预期业务结果]`；禁 HTTP 码/错误码/`待确认`/`API-AUTO`/`_id` 结尾/独立数字。
- 稳定 ID：契约 `METHOD_PATH-NNN`，业务 `BUS-<域>-NNN`；只出现在「用例ID 列 + 禅道 keyword」两处。
- 断言 v2.6 三要素：正向 `HTTP=200；code=200；data.<字段>=…`；负向 `HTTP=<code>；error.code=<词条|无（框架级）>`。
- 前置条件固定三段：环境 / 账号 / 数据；账号必须指明角色与变量名。
- 领域词典强制：断言中的大写枚举词必须属于 `docs/测试资料/领域词典.md` 的「机器可读词表：」行（R27）。
- 上传工具契约：`steps[N]`+`stepType[N]=step`+`expects[N]` 三件套缺一不可；成功判定必须 `"result":"success"`；匹配只用 keyword；请求间隔 ≥0.15s。

### lint方案.md
- R01–R30，fail 阻断上传、warn 记录理由；退出码 0/1/2。
- 覆盖矩阵 R15：每接口须齐「正向/未带Token/缺少必填参数/重复提交」四维，有分页加「边界」。

### api_testcases/README.md
- 试点与全量命令形态、manifest 幂等去重、失败证据目录、`run_automation.py` 一键管线。

## 2. case_lint.py 中硬编码元气购、必须适配的常量（关键发现）

| 位置 | 现状（元气购） | 尚品汇需求 |
|---|---|---|
| R13 fail | 路径必须以 `/v1` 开头 | `/admin/**` 与 `/api/**` |
| R14 catalog 提取 | 正则只认 `/v1/`、`/api/v1/` | `/admin/`、`/api/` |
| R23 fail | 管理端判定 `/v1/admin/` | `/admin/` |
| AUTH_EXEMPT | `/v1/auth/`、`/v1/products`… | 见 SecurityConfig 白名单 |
| BODYLESS_ACTIONS | 元气购四个端点 | 按尚品汇源码重定 |
| R28 需求编号 | 固定前缀 `AUTH\|USER\|PRD\|CART\|ORD\|PAY\|CPN\|FAV\|REV\|AS\|NT\|ADM\|SEC` | 尚品汇实际用 `RBAC/STK/PRD/ME/MALL/CFG/CUS/INF` |
| `_governance_doc` | `parents[2]/docs/测试资料/<name>` | 复制后天然成立（已按此结构落盘） |

## 3. 事实基线核实结果

| 项 | 结论 |
|---|---|
| 模板源 | `/Users/imac/Desktop/电商自动化测试/web/` 存在，scripts 15 个、治理文档 10 份 |
| 目标仓库 | `shang-pivot-master/`，**不是 git 仓库**；无既有 api_testcases / docs/测试资料 |
| Controller 数 | `@RestController` 命中 24 个（任务书写 23，差异待核对） |
| OpenAPI | 线上 60084/60085 **未暴露** `/v3/api-docs`（返回 SPA index.html）；本地 `127.0.0.1:8086/v3/api-docs` 返回 OpenAPI 3.1.0（149KB，**101 paths / 135 operations**，`/admin` 90 + `/api` 45，BearerAuth） |
| 本地运行代码一致性 | Downloads 副本 vs 目标仓库，6 个模块 src **逐模块 diff = 0**，OpenAPI 可作契约源 |
| 禅道 | `111.170.33.2:60082` 可达（302→登录页）；REST `/api.php/v1/tokens` 可签发 token |
| **禅道产品** | **product 1 = 元气购，product 2 = 尚品汇（已存在，创建于 2026-10-03，当前 0 条用例）** |
| 管理端账号 | `admin` / `Admin@123` 实测 60085 登录成功（code=0，返回 accessToken + menus + permissions） |
| C 端登录 | 手机号 + 验证码；源码 `CustomerAuthService.DEMO_CODE = "123456"`（演示环境固定码）；未知手机号自动注册；`clientType ∈ {web,h5}` |
| C 端实测 | `13800000001` + `123456` + `web` → 200，返回 accessToken，客户 id 9001（张三，NORMAL） |
| 种子会员 | 9001/13800000001、9002/13900000002、9003/13700000003、9004/13600000004、9005/13500000005(FROZEN)、9006/13400000006、9007/13300000007 |

### 鉴权白名单（SecurityConfig.java，接口用例的「免认证」判定依据）
- permitAll：`/admin/auth/login`、`/admin/auth/refresh`、`/api/auth/**`、`/doc.html`、`/knife4j/**`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/favicon.ico`、`/error`
- GET permitAll：`/admin/files/view`、`/api/files/view`、`/api/home`、`/api/site/brand`、`/api/products`、`/api/products/**`、`/api/categories`、`/api/search`
- 其余 `/admin/**` 与 `/api/**` 一律 authenticated；OPTIONS 全放行

## 4. 复制执行结果（阶段 0）

- `web/api_testcases/` → `shang-pivot-master/api_testcases/`（524K），**排除**：`artifacts/` 内容（18MB 元气购运行产物，含 `zentao_case_manifest.json`——留着会让 upload-cases 误判"已上传"而跳过）、`test_cases_inventory.md`（元气购 232 条清单）、`.DS_Store`、`__pycache__`。
- 治理文档 → `shang-pivot-master/docs/测试资料/`：rule.md、lint方案.md、评审方案.md、验收标准治理手册.md、标准-规则追溯矩阵.md。
- **未复制** 接口文档.md / 需求文档.md / 领域词典.md（元气购内容；lint 会按这些路径做 R14 接口清单与 R28 需求追溯比对，放错内容会全库误报），阶段 1 用尚品汇内容重建。

## 5. 需求编号现状（决策点 3 依据）

尚品汇需求文档共 41 个编号，前缀集合：`PRD`（5）、`RBAC`（5）、`STK`、`ME`、`MALL`、`CFG`、`CUS`、`INF`。
其中只有 `PRD` 命中 lint R28 现有白名单，其余 7 个前缀不会被追溯。

## 6. 待澄清

- 真值层级冲突：任务书要求「源码 > OpenAPI > 接口文档 > PRD」，rule.md §2 写的是「接口文档/OpenAPI > 源码，冲突时升级人工拍板」。执行按任务书，报告中标注。
- Controller 计数 23 vs 24 的差异，阶段 1 核对时澄清。
