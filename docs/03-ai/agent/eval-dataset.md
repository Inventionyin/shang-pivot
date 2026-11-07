# Agent 评测数据集：50 条怎么来、怎么扩、怎么进 CI

> 适用：V3.1 graph 路径的 Golden 回归。目标不是“堆数量”，而是每条 case 能追溯到一条业务规则或一次真实失败。

## 1. 当前 50 条构成（v31-50）

| 来源 | 条数 | 说明 |
|---|---:|---|
| 原有 Golden（E01–E20） | 20 | 售前/规格/加购/订单/知识/PII/注入/降级 |
| 真实目录价格（E21–E30） | 10 | Redmi、AirPods、iPhone 15 Pro、戴森、索尼、MacBook、Apple Watch、罗技、Xiaomi、Anker |
| 知识库政策（E31–E35） | 5 | 退款时效、质量窗口、七天无理由、定制限制、退货流程 |
| 订单/闲聊/身份/PII/注入（E36–E40） | 5 | 无订单、打招呼、身份、手机号、英文注入 |
| 多轮/加购/对比/边界（E41–E50） | 10 | 指代锚定加购、手机/耳机对比、预算、目录外、库存、政策、缺单、致谢 |

原则：

- 价格/政策/订单事实必须来自**当前种子数据**，不写“记忆中的价格”。
- 新 case 默认 `judge=false`，只跑确定性断言；语义质量用原有 20 条的裁判趋势观察。
- 每条 case 至少断言一个“可失败点”：sources、cards、answer 关键词、forbid、效率、购物车终态。

## 2. 数据集从哪里获取

### 2.1 现有自动化资产（优先级最高）

- `pytest_automation/test_cases.json`、`api_testcases/`、`ui_testcases/`：已有 800+ 用例，覆盖下单/取消/售后/地址/权限。
- `automation/` Cucumber + Playwright：74 条 e2e 场景。
- 转换方式：把“接口断言”翻译成“用户话术 + 期望轨迹”，例如：
  - pytest `API-ORDER-001`（购物车下单）→ 用户话术“我购物车里的东西想下单，怎么弄？”→ 期望 `intent=ORDER`、`getCart/submitOrder`、写操作需确认。
  - 售后用例 → “鞋不合脚，想退货” → 期望 `searchKnowledge` + `createAfterSale` + 确认。

### 2.2 生产 Trace（最真实）

现在 graph 每次运行都会写：

- `customerservice.agent_run`：intent/agent/status/token；
- `customerservice.message`：用户原话与助手回答；
- `customerservice.tool_call`：工具、参数、结果、失败码；
- `customerservice.retrieval`：query/命中/来源；
- `customerservice.pending_action`：写操作确认状态。

筛选优先级：

1. `status=HANDOFF` / `NEED_CONFIRMATION` / `FAILED`；
2. `tool_call.status != SUCCESS`（如 NOT_FOUND/TIMEOUT）；
3. `retrieval.hit=false` 或知识无命中；
4. 用户重复追问、转人工、会话结束原因；
5. 人工坐席修改过的回复。

导出脚本见 `scripts/eval/export_candidates.sql`。导出后必须：

- 手机号/地址/身份证/订单号脱敏；
- 去掉真实客户昵称、内部备注；
- 用当前种子数据复核价格/库存，过时的改成“当前真值”或丢弃。

### 2.3 知识库与商品目录

- `customerservice.knowledge_doc`：政策/FAQ 原文 → 生成 QA 对，期望 `doc:` 引用。
- `commerce.spu/sku`：名称、价格、库存 → 生成“多少钱/有货吗/什么规格”类 case。
- 注意：种子数据变更时同步更新 case（例如戴森价格从 3499 调到 3999，E24/E47 必须同步）。

### 2.4 工单与售后原因

- `customerservice.ticket` 的 title/remark；
- `commerce.after_sale` 的 type/reason；
- 转成用户口吻：“屏幕碎了想退货”“尺码不合适想换货”，补政策引用与写确认断言。

### 2.5 人工构造 / 对抗集

必须覆盖：

- 跨顾客越权（他人订单号）；
- 确认前不写、确认后只写一次、取消/过期不写；
- PII（手机号/地址）不回显；
- 中英文提示注入；
- 目录外品类、超长描述、多品类混合、指代不明；
- LLM 不可用/超时降级。

### 2.6 LLM 合成（可选，最后）

用真实 case 做种子，让 LLM 生成同义问法/错别字/口语变体；必须人工审核后才能入集，避免把模型的错误也“合成”进去。

## 3. 标注 Schema

当前 `GoldenCase`：

```java
id, businessRule, turns,
expectedSourceFragments, maxSourceCount,
expectedCardFragments, expectedCart,
expectedAnswerKeywords, forbidPatterns,
judge, judgeThreshold, evaluationSteps
```

下一步建议扩展（graph 轨迹断言）：

```java
expectedAgent       // SHOPPING_GUIDE/ORDER/AFTER_SALE/KNOWLEDGE/HANDOFF
expectedTools       // getOrder/addToCart/...
expectedStatus      // OK/NEED_CONFIRMATION/NEED_USER_INPUT/HANDOFF
requiresConfirmation
expectedActionTool  // cancelOrder/confirmReceive/createAfterSale
datasetVersion
tags/source
```

`AgentHarness` 已能解析 `done.sources`；graph 的 `done` 还带 `agent/intent/tools/runId`，可直接扩展断言。

## 4. 50 → 100 的扩法

- 每周从生产 trace 抽 10–20 条“失败/转人工/无命中/重复追问”；
- 每条失败先归类：路由错 / 槽位缺 / 工具错 / 检索错 / 话术错 / 安全；
- 只改一处，重跑对应 case；通过后入集并升 `datasetVersion`；
- 保留 20 条核心集做 PR 快速回归，50 条做周度全量，100 条做发布前全量。

## 5. 周度 CI

`.github/workflows/eval.yml`：

- 每周日 02:00（Asia/Shanghai）+ 手动触发；
- 启动全栈服务，`AGENT_ENGINE=graph`；
- 重建商品/知识索引；
- 跑 50 条 Golden，确定性断言硬门禁；`EVAL_JUDGE_GATE=false`，裁判均分只作趋势；
- 报告作为 artifact 上传；
- 3 个评估手机号轮换，规避 20 条/5 分钟限流。

本地复跑：

```bash
cd backend
EVAL_TARGET_BASE_URL=https://shang.bettery.cn \
EVAL_JUDGE_GATE=false \
EVAL_CUSTOMER_PHONES=13900009999,13900009998,13900009997 \
./mvnw -q -pl shang-customerservice -am -Peval \
  -Dtest=AgentEvalReportTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 6. 数据治理

- 不把真实客户原文直接入仓；入集前脱敏并人工复核；
- 价格/库存/政策类 case 标注“数据快照日期”，种子变更时同步；
- 模型/prompt/工具版本与 `datasetVersion` 一起记录；
- 不用评测集做 SFT；训练集与测试集去重。
