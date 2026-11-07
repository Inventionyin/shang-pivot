# Agent 方案对照备忘录：new1107 蓝图 / V3.0 现状 / V3.1 计划

- **日期**：2026-11-07
- **范围**：智能客服 Agent 运行时（`shang-customerservice`）
- **相关**：`docs/04-architecture/adr/0005-LLM网关与两阶段编排.md`、`0006-检索降级策略.md`、`0007-agent-runtime.md`、`0008-write-confirmation-idempotency.md`
- **蓝图来源**：`update/new1107/Agent与子Agent技术规划.md`、`Agent落地进展.md`、`Agent调用监控与评测落地方案.md`

> **口径声明**：本文区分“已实现”“已拍板但未实现/未接线”“明确不做”。V3.1 目前处于方案确认 + 兼容性 spike + 并行实现阶段，**不是已上线版本**；V3.0 两阶段编排仍是当前 Controller 注入的默认路径。面试或汇报时不要把这三种状态混在一起说。

## 1. 一句话结论

V3.1 不是把 new1107 蓝图照搬成“多 Agent 自治集群”，而是在现有 Java 模块化单体内做一次**可控升级**：

```text
V3.0：两阶段提示词编排（意图/槽位 → 后端执行 → 流式生成）
V3.1：显式状态图 + 4 个 LLM 子 Agent + Handoff 策略 + 自建 Tool Gateway + PostgreSQL trace
```

不引入 Python 在线服务，不依赖原生 `tool_calls`，不引入 Langfuse/ClickHouse，不在首期堆 200–500 条评测用例。

## 2. 三方对照

| 维度 | new1107 蓝图 | V3.0 已实现 | V3.1 决策 |
|---|---|---|---|
| 在线运行时 | Java Spring AI 2.x + LangGraph4j；另附 Python Phase-0 参考实现 | Java 模块化单体，单进程 | 保持 Java 模块化单体，Agent 在 `shang-customerservice` 进程内 |
| 编排 | Supervisor + 5 个子 Agent；状态机 + 检查点 | 两阶段提示词编排：`IntentRouter` 输出意图/槽位，`ChatOrchestrator` 收集事实后流式生成 | LangGraph4j 状态图；Supervisor 由“图 + 路由/预算/转人工规则”承担，不是第 5 个 LLM Agent |
| 模型访问 | Spring AI `ChatClient`，可选 MCP / Tool Calling | 自建 `LlmClient`（`RestClient` + SSE），处理 `reasoning_content` | Spring AI 2.0.2 做模型抽象与调用观测；保留 `LlmClient` 作回退/对照 |
| 工具执行 | 统一 Tool Gateway；MCP 作为工具协议 | `ChatOrchestrator` 直接调 `commerce-api`；加购内联执行 | 自建 Tool Gateway 是唯一执行面；原生 `tool_calls` 不作为生产路径 |
| 子 Agent | 5 个：导购/订单/售后/知识/Handoff | 无独立子 Agent，按意图分支 | 4 个 LLM Agent（导购/订单/售后/知识）+ 1 个确定性 Handoff 策略节点 |
| 写操作 | 模型提计划；确认 + 幂等 + 审计；首期只读 | 加购直接执行，无确认/无幂等键；取消/确认收货/售后未由 Agent 执行 | 风险分级：`WRITE_LOW` 默认执行 + 幂等；`WRITE_HIGH` 走 `pending_action` + 用户确认 + 幂等 + 审计 |
| 状态与恢复 | 状态机 + checkpointer；断点恢复 | 会话/消息落 PostgreSQL；无图检查点 | LangGraph4j + Postgres saver；checkpoint 只存摘要/槽位；写操作不因恢复重复执行 |
| Trace | Langfuse/Phoenix + PostgreSQL + ClickHouse（规模上来后）；OTel/SkyWalking | `audit_log` + `message.tool_trace` + token 审计；无 `agent_run` 表 | PostgreSQL 自建表：`agent_run` / `agent_step` / `llm_call` / `tool_call` / `retrieval` / `guardrail_event`；不引入 Langfuse/ClickHouse |
| 评测 | 首期 200–500 条 golden cases；CI 门禁 | 50 条 Golden cases（20 核心 + 30 扩容）；确定性断言硬门禁 + LLM Judge 趋势；每周 CI | 先守住 50 条高频/高风险用例，从失败 trace/工单逐步扩充；不追求首期数量 |
| RAG | pgvector + BM25 混合、引用、拒答 | 词法兜底 + 可插拔向量；`auto` 模式；引用来源 | 沿用 `ADR-0006`，本版不重做 RAG |
| 协议 | MCP 管工具，A2A 管跨团队 Agent；首期不引入 A2A | 进程内 Java 接口 | 进程内 Java 接口；MCP 只做可选外部/QA 工具；A2A 不引入 |
| 新增基础设施 | Langfuse/ClickHouse、Python 训练链路 | 无新增（Postgres/Redis/MinIO 已有） | 只新增 PostgreSQL 表；不新增中间件、不新增 Python 在线服务 |
| 模型数据 | SFT/DPO/蒸馏分阶段推进 | 未开始 | 不在 V3.1 范围；先积累 trace、反馈与评测集 |

## 3. 采纳什么

| 采纳项 | 蓝图来源 | V3.1 怎么落地 | 当前状态（2026-11-07） |
|---|---|---|---|
| Tool Gateway | 蓝图 §4/§7：工具网关是唯一执行面，模型不能拼 SQL/任意发 HTTP | 自建 `ToolDefinition` / `ToolExecutor` / `ToolResult` / `ToolContext` / `ToolRegistry`；统一权限、Schema、超时、风险、幂等、trace | 已实现 11 个工具；`ToolRegistry` 5 条单测 + graph 8 条集成测试通过；legacy 路径仍用内联工具（回退） |
| 子 Agent 契约 | 蓝图 §5.2：每个子 Agent 声明 `allowed_tools` / `max_tool_steps` / `timeout_ms` / `fallback_policy` | `AgentName` / `AgentStatus` / `SupervisorAgent.RouteResult` / `ToolResult` 等价契约；4 个 Agent + Handoff | 已实现；未单独建 `AgentTask/AgentResult` 类，避免重复建模 |
| 显式状态机与检查点 | 蓝图 §6.2：`ROUTING → NEED_SLOT → PLANNING → TOOL_CALLING → WAIT_CONFIRM → ANSWERING → ESCALATED` | LangGraph4j 状态图；条件边；Handoff；V3.0 两阶段作为回退 | `route → execute → answer` + 条件边已实现并有集成测试；默认入口仍是 legacy；Postgres checkpoint 未接（MemorySaver） |
| 全链路 trace | 蓝图 §13：`traceId + sessionId + agentRunId + toolCallId`；每一步可追溯 | V25 表结构；流式结束后聚合 `llm_call`；敏感字段脱敏；大字段可转 MinIO | V25 已在 PG17 验证；`AgentTraceService` + `CsAgentTraceController` 已接线；MinIO 大字段未接 |
| 评测闭环 | 蓝图 §13.2/§13.3：golden set、失败分类、CI 门禁 | 50 条 Golden cases；确定性断言硬门禁，Judge 只作趋势；每周 CI | graph 周度 CI 已接（`AGENT_ENGINE=graph`，3 个账号轮换）；50 条生产/本地确定性断言通过；10/10 写安全矩阵通过 |
| 写操作安全 | 蓝图 §0.3/§12：模型只提计划，确认 + 幂等 + 审计 + 人工兜底 | 风险分级；`pending_action` 状态机；唯一幂等键；确认接口；审计事件 | 已实现并通过集成测试；确认/取消 HTTP 接口已接线；失败保持 CONFIRMED 可重试 |
| 降级与回退 | 蓝图 §11：LLM/工具失败可降级 | 图运行时异常回退 V3.0 两阶段；LLM 不可用走规则/FAQ；写操作 fail closed | LLM 不可用/异常有 fallback；图运行时不自动回退 legacy，需开关 `AGENT_ENGINE=legacy` 回滚 |

## 4. 明确不采纳什么

| 不采纳项 | 蓝图中的主张 | 理由 | 重新评估触发条件 |
|---|---|---|---|
| Python 独立运行时 | 蓝图建议 Python 侧做训练/蒸馏，并给出 Python Phase-0 参考实现 | 在线链路拆成 Java + Python 双栈，会带来部署、鉴权、观测、数据一致性与值班成本；当前流量与团队规模不需要 | 需要在线训练/推理服务、GPU 弹性、跨语言模型能力，且能承担双栈运维时 |
| MCP/A2A 上热路径 | 蓝图建议 MCP 管工具、A2A 管 Agent 互操作，首期不引入 A2A | 单体内部进程内接口延迟最低；MCP 多一跳且网关对工具协议支持不稳定；A2A 在单应用内没有对端 | 工具需要被外部 Agent/IDE 复用，或 Agent 跨团队/跨语言部署时；MCP 只做可选暴露 |
| 5 个 LLM 子 Agent | 蓝图列了 5 个 Agent（含 `HandoffAgent`） | Handoff 是确定性策略（显式请求、投诉、连续未解决、低置信度、高危、工具失败），不需要第 5 次 LLM 调用；蓝图本身也允许作为策略 | 转人工判断需要复杂语义理解，且规则误判率被数据证明不可接受时 |
| Langfuse / ClickHouse | 蓝图建议 Langfuse/Phoenix 做 LLM trace，ClickHouse 做规模分析 | 当前数据量下 PostgreSQL 足够；多一套中间件意味着部署、备份、权限、PII 合规与值班成本；自建表已能按 `run_id`/`session_id`/`status` 查询 | 出现高频事件分析、prompt 版本管理、人工标注/实验对比的硬需求，且 PostgreSQL 查询/存储成为瓶颈时 |
| 首期 200–500 条 golden cases | 蓝图 §13.2 建议从 pytest/工单/人工构造 200–500 条 | 没有稳定流量与失败分类前，堆数量只会增加维护成本；先用 20 条覆盖高频与高风险路径，再用真实失败扩充 | 进入 Phase 2/3，日会话量与失败样本足够，且已有标注/归因流程时 |
| 原生 `tool_calls` 作为生产路径 | 蓝图把 Spring AI Tool Calling / MCP 作为工具调用方式 | 当前 web-to-API 网关对 `tools`/`tool_calls` 支持不可靠（ADR-0005 实测）；生产执行面不能建立在不稳定协议上 | 网关稳定支持原生函数调用，且经过多轮兼容性与回归验证后 |
| 全自治多 Agent 集群 / 自动写 | 蓝图明确“不要一上来做大而全的多 Agent 自治集群”；写操作必须确认 | 成本、不可预测性、越权与审计风险都不可控；首期只做有边界的 4 个角色 | 单 Agent 边界与评测稳定后，再按领域增量扩展，不做无边界 swarm |

## 5. 面试怎么讲：问题 → 架构 → 取舍 → 数据 → 结果

### 5.1 五段式

| 段 | 要讲什么 | 本项目素材 | 边界 |
|---|---|---|---|
| 问题 | 单 Agent 工具太多、上下文太杂；跨订单/物流/售后/商品；网关原生 `tool_calls` 不可靠；写操作有真实副作用 | V3.0 两阶段编排能跑通，但所有意图集中在 `ChatOrchestrator`；加购直接执行；取消/售后未开放 | 不要把它说成“已经多 Agent” |
| 架构 | Supervisor 管状态，子 Agent 管领域，Tool Gateway 管权限与执行，Postgres 管 trace | 状态图 + 4 个 LLM Agent + Handoff 策略 + Tool Gateway + `agent_run` 表 | 说明 Supervisor 是图/规则，不是第 5 个 LLM |
| 取舍 | 为什么单体、为什么 4 个、为什么 Postgres、为什么 20 条、为什么不用 Python | 见 §2/§4 的对照表；每个“不做”都给触发条件 | 取舍要能说出“什么情况下会改” |
| 数据 | 用什么指标证明不是“感觉更聪明” | 50 条 Golden；确定性断言（工具/卡片/关键词/禁止模式）；Judge 趋势；首字/P95/token/转人工率/写操作审计覆盖率 | 只报真实跑过的数据；生产 legacy/graph 均 20/20，扩容 50 条已跑通 |
| 结果 | 已实现什么、目标是什么、还差什么 | V3.0 两阶段 + V3.1 graph 均已上线；50 条 Golden、10 条写安全、管理端 Trace 已落地；Postgres checkpoint/50→100 扩集待补 | 明确“已上线/已拍板/已验证/未实现”四层状态 |

### 5.2 常见追问与回答要点

| 追问 | 回答要点 |
|---|---|
| 为什么不用 Python LangGraph？ | 在线主应用是 Java/Spring，Python 会引入双栈部署、鉴权、trace 与数据一致性成本；LangGraph4j 足够表达状态图/检查点，且能复用 Spring AI 与现有事务。Python 只保留离线评测/训练的可能性 |
| 为什么不是 5 个 Agent？ | Handoff 是策略不是领域能力；用规则判定更快、更可解释、更便宜。蓝图本身也说 Handoff 可以不单独做 Agent |
| 为什么不用 Langfuse？ | 当前规模 PostgreSQL 够用；Langfuse 带来额外中间件、PII 与值班成本。等出现 prompt 实验/人工标注/高频分析需求再评估 |
| 为什么只有 20 条用例？ | 先覆盖高频与高风险，确保每条都能定位失败原因；用例从真实失败 trace/工单扩充，比一次性堆 500 条更有维护价值 |
| 为什么还留在单体？ | 模块化单体已有编译期边界与进程内低延迟；独立 Agent 服务在当前体量下收益小于运维成本。拆分触发条件写在 ADR-0001/0007 |
| 网关不支持 `tool_calls` 怎么办？ | 不把生产执行面建立在它上面；模型输出结构化计划，后端校验后由 Tool Gateway 执行。代价是多一次往返与 JSON 容错 |
| 写操作怎么保证不重复？ | 服务端幂等键 + `pending_action` 唯一索引 + CAS 确认 + `tool_call` 部分唯一索引 + 领域状态守卫；崩溃窄窗口用领域幂等/可逆性兜底，不引入分布式事务 |

### 5.3 90 秒口述稿（可按需删减）

> “V3.0 用两阶段编排解决了网关不支持原生 tool_calls 的问题：先用轻量提示词做意图和槽位，后端执行 commerce/RAG，再让模型流式生成。它能跑通，但所有意图都堆在一个编排器里，写操作也没有统一的确认和幂等。V3.1 在同一个 Java 单体里引入 LangGraph4j 状态图和 4 个有边界的子 Agent，Supervisor 负责状态、预算和转人工，Handoff 用规则而不是第 5 次 LLM 调用；所有工具只能经过自建 Tool Gateway，模型只提计划，高风险写操作要用户确认并带幂等键；trace 全部落 PostgreSQL。我们没有拆 Python 服务，没有把 MCP/A2A 放进热路径，也没有为了好看上 Langfuse/ClickHouse。验证上先用 20 条 Golden 做确定性门禁，再逐步从失败样本扩充。当前 V3.0 仍是默认入口；V3.1 的兼容性 spike 3/3、V25 在 PostgreSQL 17 验证、图编排 8 条集成测试通过，写确认 HTTP 接口与 Trace 查询已接线；生产切换还需要 graph 周度评测与灰度，Postgres checkpoint 尚未接入。”

## 6. 现状与边界（避免过度承诺）

> 本节是 2026-11-07 的仓库快照。V3.1 核心代码已落地并通过本地测试；最终以合并后的代码与 CI 结果为准。

| 能力 | 状态 | 证据/路径 |
|---|---|---|
| V3.0 两阶段编排 | 已实现，当前 Controller 注入的默认路径 | `backend/shang-customerservice/src/main/java/com/shangpivot/customerservice/agent/ChatOrchestrator.java`、`ChatController.java` |
| 意图识别（含 SPECS/ADDCART） | 已实现 | `IntentRouter.java` |
| 对话内加购 | V3.0 内联调用；V3.1 `AddToCartTool` 已接入 graph 路径并通过集成测试 | `ChatOrchestrator.addCartFacts()`、`agent/tool/impl/AddToCartTool.java` |
| 50 条 Golden 评测 + graph 每周 CI | 已实现 | `eval/GoldenDataset.java`、`AgentEvalReportTest.java`、`.github/workflows/eval.yml`、`eval-dataset.md` |
| Graph Golden 对等 | 20/20 确定性断言通过（不跑裁判） | `GraphGoldenIntegrationTest` |
| 写操作安全矩阵 | 10/10（确认前不写/确认一次/幂等/取消/过期/越权/缺参） | `AgentWriteSafetyIntegrationTest` |
| Spring AI 2.0.2 + Boot 4.1.1 + LangGraph4j 1.9.8 兼容性 | 已验证（3 个 spike 用例通过） | `AgentRuntimeSpikeTest.java` |
| Spring AI 适配层 | 已实现 `SpringAiLlmClient`，`AGENT_LLM_CLIENT=spring-ai` 切换；流式 reasoning diff 单测通过 | `llm/SpringAiLlmClient.java`、`SpringAiLlmClientTest` |
| 子 Agent 名称/状态契约 | 已实现枚举 + `RouteResult`/`AgentStatus` 等价契约 | `agent/contract/AgentName.java`、`AgentStatus.java` |
| Tool Gateway + 工具实现 | 11 个工具 + 权限/超时/风险闸门；`ToolRegistry` 5 条单测 | `agent/tool/*.java`、`agent/tool/impl/*.java` |
| V25 trace / `pending_action` 表 | 已在 PostgreSQL 17 执行并验证（8 张表 + JSONB 往返） | `V25__v31_agent_runtime.sql`、`AgentTracePersistenceTest` |
| LangGraph4j 图编排 | `route → execute → answer` + 条件边 + Handoff；8 条集成测试通过；默认入口仍为 legacy | `agent/graph/AgentGraphOrchestrator.java`、`AgentGraphIntegrationTest` |
| Postgres checkpoint | 未接入；当前图编排使用 `MemorySaver` | `AgentGraphOrchestrator.init()` |
| trace 写入 / 查询 | `AgentTraceService` + `CsAgentTraceController` 已接线 | `agent/trace/AgentTraceService.java` |
| 4 个角色提示词 | 已实现 | `agent/graph/AgentPromptFactory.java` |
| Handoff 策略节点 | 已实现（显式转人工/投诉/负面情绪→QUEUED） | `agent/graph/HandoffPolicy.java` |
| 写操作确认服务 | 已实现创建/复用、CAS 确认、取消、TTL、失败保持 CONFIRMED；HTTP 接口已接线 | `agent/service/PendingActionService.java`、ADR-0008 |
| 生产状态 | 2026-11-07 20:16 起 `AGENT_ENGINE=graph`；legacy 与 graph 生产 Golden 均 20/20 确定性通过；代码默认仍 `legacy` | `.env`、`deploy.md` |
| Langfuse / ClickHouse / Python 在线服务 | 明确不采用 | 见 §4 |

## 7. 参考

- `docs/04-architecture/adr/0005-LLM网关与两阶段编排.md`：V3.0 两阶段编排与网关约束
- `docs/04-architecture/adr/0006-检索降级策略.md`：词法兜底 + 可插拔向量
- `docs/04-architecture/adr/0007-agent-runtime.md`：V3.1 运行时选型与验证
- `docs/04-architecture/adr/0008-write-confirmation-idempotency.md`：写操作分级确认与幂等
- `update/new1107/Agent与子Agent技术规划.md`：蓝图原文
- `update/new1107/Agent落地进展.md`：Python Phase-0 参考实现与实测记录（仅作参考，不是本仓库在线链路）
- `update/new1107/Agent调用监控与评测落地方案.md`：监控/评测方案原文
