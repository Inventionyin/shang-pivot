# 测试用例设计 Agent 规范

> 本文件是"生成/评审测试用例"的唯一规范入口，适用于 `pytest_automation`、`api_testcases` 及未来新增的测试资产。
> 任何由 AI 或人新增的用例，必须满足本文件的约束，并通过 `pytest_automation/scripts/case_quality_gate.py` 与 `review_cases.py --check`。

## 1. 事实源优先级

1. **源码**：Controller 注解、DTO 校验、Service 分支、Flyway 迁移（表/列/约束）；
2. **机读模型**：`api_testcases/artifacts/endpoint-model.json`、`schema-model.json`、`field-map.json`、`call-graph.json`、`docs/openapi-v3-api-docs.json`；
3. **需求**：`docs/01-requirements/*`、`docs/测试资料/需求文档.md`；
4. **运行事实**：`artifacts/case_endpoint_map.json`、Allure 结果、DB 实际数据。

规则：**不得臆造接口、字段、状态或校验规则**；每条断言必须能指回上述来源之一（在 `verify` 中写清依据）。

## 2. 用例设计方法（必须说明用了哪一种）

| 方法 | 适用 | 产出 |
|---|---|---|
| 等价类划分 | 参数取值域（合法/非法类） | 每类至少 1 条，标出类边界 |
| 边界值分析 | 长度、数值、分页、库存 | 上界、上界+1、下界、0、负数 |
| 判定表 / 组合 | 多字段联动（登录、下单、售后） | 条件桩 × 动作桩，覆盖关键组合 |
| pairwise | 参数多但交互弱 | 两两组合覆盖 |
| 状态机 | 订单、售后、支付 | 合法跃迁 + 非法跃迁 |
| 错误猜测 | 空、重复、超长、畸形 JSON、越权 | 负向用例 |
| 属性/不变量 | 库存、金额、幂等 | 任意操作后不变量成立 |

每条用例的 `verify` 必须写明：**输入、预期结果、判定依据（源码/约束/需求）**。

## 3. 用例结构与命名

- 用例函数名：`test_<行为>_<条件>`，一个函数只验证一个主断言，可含必要的辅助断言；
- 与接口相关的用例必须能被记录器归属到一个主体接口（实际调用即可，无需手工标注）；
- 稳定编号由 `data/case_registry.json` 分配，**禁止手工改编号**；新增用例先跑 `export_cases.py`；
- 必须在 `data/case_meta.yaml` 登记 `title` 与 `verify`（参数化用例登记函数级，参数标题用 `param_titles`）；
- 每个用例必须挂优先级 marker：`P0`（资金/库存/认证/下单/支付）、`P1`（核心查询/管理端写）、`P2`（边界/提示文案）。

## 4. 测试数据规则

- 禁止依赖固定演示账号做写操作：会员一律随机注册（`data_factory.unique_phone`），管理端只读可用固定账号；
- 禁止硬编码业务主键（SPU/SKU/订单/地址 ID）；从 DB 或 API 动态选取（`db.pick_on_sale_sku` 等）；
- 下单必须带 `idempotentKey`；写操作要能被清理或由基线恢复兜底；
- 共享状态（库存 SKU、管理端登录失败计数、全局配置）必须走 worker 分片或 `@pytest.mark.serial`；
- 并行默认按主体接口分组（`xdist_group=ep::<接口>`），同接口用例串行、不同接口并行。

## 5. 断言分层（最低要求）

| 层 | 必填 | 说明 |
|---|---|---|
| HTTP | 是 | 状态码 + `Content-Type`（JSON 接口） |
| 业务码 | 是 | 成功 `0`；业务失败 HTTP 200 + 数值码；参数错误 400 + 40000 |
| 结构 | 是 | 关键字段存在/类型（Long 序列化为字符串） |
| 业务语义 | 是 | 状态、金额、库存增量、幂等结果 |
| 数据库 | **写接口必填** | 请求入参 ↔ 落库行逐字段比对（见 §6） |
| 不变量 | 核心链路必填 | 库存守恒、金额守恒、状态机 |

禁止只断言 HTTP 200 或只断言 `code==0` 的"弱断言"。

## 6. 写接口 DB 校验（强制）

- 写端点（POST/PUT/DELETE，含管理端 CRUD）必须至少一条"请求 → DB"断言；
- 定位刚写入行：优先返回 ID；BaseEntity 表可用 `trace_id`；否则用自然唯一键（手机号、cart+sku、idempotent_key）；
- 比对规则：Long 主键按字符串比、`NUMERIC` 用 Decimal、JSONB 按 JSON 语义、软删过滤 `deleted=false`、系统生成列（id/order_no/status/金额/时间/trace_id）不参与与入参比对但要断言非空；
- 统一使用 `helpers/db_contract.py` 的 `assert_persisted(...)`，禁止在用例里散写 SQL。

## 7. 请求方式与接口归属校验

- 每条 API 测试点必须能映射到 `endpoint-model.json` 中的 `METHOD + path`，且实际调用方法必须一致；
- 夹具驱动/仅 DB 校验的用例，需在 `data/endpoint_attribution.yaml` 显式补归属；
- 新增接口后必须重跑 `build_endpoint_matrix.py`，把安全/逆向/边界/异常巡检补齐。

## 8. 覆盖维度清单（每条用例需归入其一）

功能正向、功能逆向、安全（鉴权/越权/注入）、边界值、参数组合、异常（畸形报文/类型错误）、幂等、大数据量、业务不变量、中间件（Redis/MQ）。

## 9. 完成定义（DoD）

1. 代码 + `case_meta` + 稳定编号（注册表）；
2. 质量门禁通过（`case_quality_gate.py`）；
3. 评审通过（新增/修改用例 `approved`，P0 默认全评）；
4. 串行与并行各跑一轮，结果一致；
5. 报告可见：标题、验证点、请求/响应、失败证据、DB 校验标记。

## 10. AI 生成约束（硬性）

- 生成前先读源码/模型，不得凭经验造字段；
- 生成后必须输出：用例文件、`case_meta` 条目、预期覆盖维度、依赖数据说明；
- 必须运行：`export_cases.py` → `case_quality_gate.py` → `review_cases.py --check`；
- 修改已有用例必须更新 `content_hash` 并重新评审，禁止只改断言不改元数据；
- 不得引入不可复现的随机/时间依赖；不得依赖执行顺序；
- 生成的参数化用例若超过 20 条，必须提供参数数据文件或生成器脚本。

## 11. 评审流程

1. `export_cases.py` 生成/更新用例库与注册表；
2. `review_cases.py` 生成评审单（P0 全评、P1 30%、P2 10% 抽样，新增/修改全评）；
3. 评审人填写结论（approved/rejected + 意见），`--apply` 回填；
4. CI 执行 `review_cases.py --check`（夜间 `--strict-p0`）；
5. 被 rejected 的用例必须修改或标记 retired。

详见 `pytest_automation/docs/用例评审规范.md` 与 `pytest_automation/scripts/review_cases.py`。
