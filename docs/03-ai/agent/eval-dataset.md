# Agent 评测数据集：200 条怎么来、怎么扩、怎么进 CI

> 适用：V3.1 graph 路径的 Golden 回归。目标不是“堆数量”，而是每条 case 能追溯到一条业务规则或一次真实失败。公开来源与许可见 `eval-sources.md`。

## 1. 当前 200 条构成（v32-200）

| 来源 | 条数 | 说明 |
|---|---:|---|
| 原有 Golden（E01–E20） | 20 | 售前/规格/加购/订单/知识/PII/注入/降级 |
| 真实目录价格/规格/库存（E21–E30、E51–E65、E101–E130） | 55 | 覆盖生产 30 个在售 SPU 的价格、规格、库存与加购 |
| 知识库政策（E31–E35、E75–E82、E131–E150） | 33 | 退款时效/质量窗口/七天无理由/定制/激活/贴身衣物/运费/流程 |
| 订单/闲聊/身份/PII/注入/转人工（E36–E40、E83–E94、E151–E165、E186–E195） | 42 | 无订单、身份、手机号/身份证/地址、中英文注入、转人工、投诉 |
| 多轮/加购/对比/预算/边界（E41–E50、E66–E74、E95–E100、E166–E185、E196–E200） | 50 | 指代锚定、数量/单位、跨品类对比、预算、目录外、提示词变体 |

原则：

- 价格/政策/订单事实必须来自**当前种子数据**，不写“记忆中的价格”。
- 新 case 默认 `judge=false`，只跑确定性断言；语义质量用 E01–E20 的裁判趋势观察。
- 每条 case 至少断言一个“可失败点”：sources、cards、answer 关键词、forbid、效率、购物车终态。
- 加购类 case 同时校验 `cart:` 来源与购物车终态数量，覆盖“来一台/来三个/来两件”等数量表达。
- 加购确认口径（v3.4.1 统一）：金额达到 500 元或数量大于 1 → 期望 `cart-confirm:` 待确认动作，不写购物车；金额低于 500 且数量为 1 → 期望 `cart:` 直接加购（E182 收纳盒 69 元、E185 洁面乳 79 元）。
- 商品名中的数字（Mate X5、iPhone 15）不得被误解析为购买数量，`IntentRouterTest` 已固化。

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

## 4. 100 → 200 的扩法

- 每周从生产 trace 抽 10–20 条“失败/转人工/无命中/重复追问”；
- 每条失败先归类：路由错 / 槽位缺 / 工具错 / 检索错 / 话术错 / 安全；
- 只改一处，重跑对应 case；通过后入集并升 `datasetVersion`；
- 保留 20 条核心集做 PR 快速回归，200 条做周度/发布前全量（100 条中档集保留用于快速冒烟）。

### 4.1 v31-100 的实际扩集路径（2026-11-07）

1. **目录对齐**：直接从生产 `commerce.spu/sku` 导出 30 个 `status=ON` 的 SPU 与最低价，补齐 E51–E60/E98，删掉“记忆价格”；
2. **SKU/库存**：E61–E65 覆盖折叠屏、洗衣机、扫拖、显示器，断言 `sku:` 来源与真实价格；
3. **对比**：E66–E69/E99 用同一检索轮次返回两个真实商品卡片，防止“只答一个”；
4. **数量/单位**：E70–E74/E96 覆盖 `来一台/来三个/来一件/来两件`，同时断言购物车终态；
5. **政策**：E75–E82 从 `knowledge_doc` 原文抽关键词（原路/商家/不支持/寄回/二次销售）；
6. **订单/转人工**：E83–E88 覆盖无订单、单号不存在、显式转人工、投诉负向转人工；
7. **安全**：E91–E94 覆盖中英文注入、身份证、地址；PII 在入口 `maskPii` 已掩码；
8. **回归中发现的真实 bug**：
   - `extractQty` 把商品名里的 `X5/15` 当数量 → 已改为“购买动词后的数字才算数量”，并补 `IntentRouterTest`；
   - 评测 harness 复用旧会话导致跨用例卡片/指代污染，且转人工后的 QUEUED 会话会吞掉后续用例 → 新增 `POST /chat/sessions?fresh=true`（结束进行中会话再建新会话），harness 每个用例强制新会话；
   - `resolveProduct` 用商品名前 4 字匹配，品牌前缀导致“收纳盒”匹配失败 → 改为检索关键词命中商品名即可（`name.contains(keyword)`），并扩大候选到 5 条；
   - 对比/二选一（“哪个吸力好/哪个便宜”）被属性词误判成单商品 SPECS → 规则层强制走商品检索，确保两张卡；
   - 多规格商品（跑步鞋/帐篷）未指定规格时，原逻辑会静默不写；现返回 `sku:` 来源并反问规格，评测同时覆盖“不擅自加购”。

> 扩展时同步更新 `GraphGoldenIntegrationTest.CATALOG`（与生产 30 个在售 SPU 对齐），否则本地确定性回归会与生产目录漂移。

### 4.2 v32-200 的扩集路径（2026-11-08）

1. **目录全覆盖**：E101–E130 为 30 个在售 SPU 补规格/库存/价格/加购断言，所有期望值来自当前 `commerce.spu/sku`；
2. **政策变体**：E131–E150 覆盖退款时效、原路退回、二次销售、定制/激活/贴身衣物限制、流程/审核/验收；
3. **订单与转人工**：E151–E165 覆盖无订单、单号不存在、显式转人工、投诉、身份与闲聊；
4. **多轮/对比/预算**：E166–E185 覆盖跨品类对比、预算约束、数量单位、目录外；
5. **安全对抗**：E186–E195 覆盖中英文注入、手机号/身份证/地址、外部平台、不存在订单的取消/确认/退款；
6. **提示词变体**：E196–E200 覆盖通勤/拍照/降噪、大小写、给父母买等自然表达；
7. **公开来源**：τ-bench/ToolBench 只迁移轨迹模式，prompts.chat/LangGPT 只迁移结构；来源与许可见 `eval-sources.md`。

## 5. 周度 CI

`.github/workflows/eval.yml`：

- 每周日 02:00（Asia/Shanghai）+ 手动触发；
- 启动全栈服务，`AGENT_ENGINE=graph`（本地发布前再跑一遍 legacy）；
- 重建商品/知识索引；
- 跑 200 条 Golden，确定性断言硬门禁；`EVAL_JUDGE_GATE=false`，裁判均分只作趋势；
- 报告作为 artifact 上传；
- 3 个评估手机号轮换，规避 20 条/5 分钟限流（200 条约 240 轮，需 4–5 个限流窗口）；
- harness 每个用例调用 `POST /api/chat/sessions?fresh=true`，保证独立会话与购物车，不受上一用例卡片/转人工状态影响。

### 5.1 导出给外部测试平台

```bash
cd backend
./mvnw -q -pl shang-customerservice -am test \
  -Dtest=GoldenJsonlExportTest \
  -Dgolden.output=../../scripts/eval/golden-200.jsonl \
  -Dsurefire.failIfNoSpecifiedTests=false
```

产物：[`scripts/eval/golden-200.jsonl`](../../../scripts/eval/golden-200.jsonl)，200 行、200 个稳定 `sourceId`、20 条 Judge、860 条 checks。字段包含 `sourceId/datasetVersion/turns/expected/checks/tags/judge`，可直接由平台同步脚本按 `sourceId` upsert。

本地复跑：

```bash
cd backend
EVAL_TARGET_BASE_URL=https://shang.bettery.cn \
EVAL_JUDGE_GATE=false \
EVAL_CUSTOMER_PHONES=13900009999,13900009998,13900009997 \
./mvnw -q -pl shang-customerservice -am -Peval \
  -Dtest=AgentEvalReportTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 5.2 会话连续性专项数据集（v33-session）

`golden-200` 保持不变；新增独立 6 条 suite，覆盖同一会话内多轮上下文延续（规格追问、加购、商品切换、价格切换、指代不明反问、上一轮商品直接购买）：

```bash
cd backend
./mvnw -q -pl shang-customerservice -am test \
  -Dtest=SessionContinuityExportTest \
  -Dsession.output=../../scripts/eval/session-continuity-6.jsonl \
  -Dsurefire.failIfNoSpecifiedTests=false
```

产物：[`scripts/eval/session-continuity-6.jsonl`](../../../scripts/eval/session-continuity-6.jsonl)，6 行、`datasetVersion=v33-session`、`suite=session-continuity`。平台导入后按 `sourceId` upsert，作为 200 条全量之外的附加 suite 运行。

> 浏览器层的“进商品详情再返回、刷新、新对话”由 `automation/` 的 Playwright 场景 `15-mall-ai-chat.feature` 覆盖；Golden/平台数据集只覆盖同一会话的 API/Agent 连续性，不能替代 UI 路由回归。

### 5.3 全品类指代加购专项数据集（v35-refcart，50 条）

`reference-cart-50` 与 `golden-200` 分开维护，专门验证“上文商品 → 现在买/加进购物车”在 30 个在售 SPU 上都能命中，而不是只对冰箱生效：

- **30 条 SPU 点名**：每个在售 SPU 一条“把{商品名}加进购物车”；单 SKU 断言 `cart-confirm:`/`cart:`，多 SKU 断言 `sku:` + 规格反问。
- **180 条 JUnit 矩阵**：30 SPU × 6 种“上文指代”说法，覆盖“现在买/买吧/就这个/要这个/把它加进购物车/上面那个加进购物车”。
- **10 条品类/品牌指代**：手机、电脑、耳机、手表、华为、小米、苹果、联想、戴森、海尔；唯一候选直接加购，多候选必须 `context-ask` 反问。
- **10 条边界对抗**：第二个、iPhone 15 不得命中 Pro、多卡“它”、无上文点名、帐篷/跑步鞋多规格、收纳盒直加、冰箱确认、目录外洗碗机、“我想买手机”非加购。

```bash
cd backend
./mvnw -q -pl shang-customerservice -am test \
  -Dtest=ReferenceCartExportTest \
  -Dreference.output=../../scripts/eval/reference-cart-50.jsonl \
  -Dsurefire.failIfNoSpecifiedTests=false
```

产物：[`scripts/eval/reference-cart-50.jsonl`](../../../scripts/eval/reference-cart-50.jsonl)，50 行、`datasetVersion=v35-refcart`、`suite=reference-cart`；平台 `version` 列写 `v35ref`（VARCHAR(10) 限制）。

> 30×6=180 条“商品×说法”矩阵在 `ProductReferenceResolverTest` 中跑，属于每次 PR 的确定性门禁；平台 50 条只做端到端代表性验证，避免把 180 条重复搬上平台。

### 5.4 目录驱动与评测自动化（P1）

- `scripts/eval/catalog_snapshot.py`：从管理端 `/admin/products?status=ON` 导出在售 SPU/SKU 快照。
- `scripts/eval/generate_reference_cases.py`：每个 ON SPU 生成一条 `REF-<spuId>-named-add`，输出独立 suite `reference-cart-auto`（`v36-refcart-auto`）。
- `scripts/eval/check_reference_coverage.py`：覆盖率门禁；缺失 SPU、重复 sourceId、用例缺可失败断言都会退出 1。
- `scripts/eval/mine_failure_candidates.py`：只读挖掘 `FAILED/HANDOFF/tool_error/retrieval_miss`，脱敏后输出候选，人工复核后入集。
- `scripts/eval/sync_platform_cases.py`：按 `(targetId, sourceId)` upsert 到 AI 测试平台；凭据只走环境变量。

每周 `.github/workflows/eval.yml` 自动执行脚本单测、目录快照、auto 用例生成、覆盖率门禁，并上传覆盖率报告与候选样本。当前生产目录 30 个 ON SPU 覆盖 **30/30**；新增 SPU 后重新生成并同步即可，不需要改手写 50 条。


## 6. 数据治理

- 不把真实客户原文直接入仓；入集前脱敏并人工复核；
- 价格/库存/政策类 case 标注“数据快照日期”，种子变更时同步；
- 模型/prompt/工具版本与 `datasetVersion` 一起记录；
- 不用评测集做 SFT；训练集与测试集去重。
