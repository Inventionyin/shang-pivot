# ADR-0007：Agent 运行时——Java 模块化单体内的 Spring AI + LangGraph4j + 自建 Tool Gateway

- **状态**：Accepted
- **日期**：2026-11-07
- **决策范围**：V3.1 Agent 运行时（智能客服）
- **相关**：`ADR-0001`、`ADR-0002`、`ADR-0004`、`ADR-0005`、`ADR-0006`、`ADR-0008`；`update/new1107/Agent与子Agent技术规划.md`；`docs/03-ai/agent/README.md`；`backend/shang-server/src/main/resources/db/migration/V25__v31_agent_runtime.sql`

## 背景（Context）

### V3.0 已落地的两阶段编排

V3.0 按 `ADR-0005` 采用**两阶段提示词编排**，不依赖网关原生 `tool_calls`：

1. **阶段一（意图与槽位）**：`IntentRouter` 用轻量提示词输出 JSON `{intent, slots}`；显式订单号、加购/规格等强特征词先走规则短路，未命中才调用模型。
2. **阶段二（执行与生成）**：后端按意图执行 `commerce-api` / `identity-api` / RAG 检索，把结构化事实拼入上下文，再让模型流式生成回答。
3. **流式协议**：SSE 区分 `reasoning`（思考增量）与 `delta`（正文增量），以 `cards` 下发真实商品卡片，以 `done` 收尾。
4. **降级链**：LLM 不可用或工具失败时按 `ADR-0006` 与 PRD §8.13 返回规则/词法兜底话术，会话不中断。

`ChatOrchestrator` 是当前唯一编排入口，已承载售前推荐、订单查询、售后知识、规格追问、对话内加购等能力；V3.0 的 20 条 Golden 评估用例与每周 CI 评估也已落地。V3.1 不能为了“多 Agent”推倒重来，只能在保持可回退的前提下增量演进。

### 外部约束

1. **网关原生 `tool_calls` 不可靠**：当前使用 OpenAI 兼容的 web-to-API 网关，`tools` / `tool_calls` 支持不稳定（`ADR-0005` 实测）。原生函数调用不能作为生产执行面。
2. **网关是思考型模型**：先输出 `reasoning_content` 再输出正文；运行时必须显式解析、分流，不能假设只有 `content`。
3. **网关无 embeddings 端点**：检索仍按 `ADR-0006` 走词法兜底 + 可插拔向量，不在本 ADR 改变。
4. **部署形态是模块化单体**：按 `ADR-0001` / `ADR-0004`，`shang-customerservice` 与 `commerce`、`identity` 同进程部署；跨上下文只走 `*-api`，不跨 schema 直读。当前没有独立 Agent 网关进程，也没有 Python 运行时。
5. **既有非功能约束不变**：首字尽量 < 2s；工具读 1s / 写 3s 超时；写操作必须可确认、可审计；故障必须可降级。

### new1107 蓝图提案

`update/new1107/Agent与子Agent技术规划.md` 提出：

```text
1 个 Supervisor（主 Agent / 编排器）
  + 5 个有明确边界的子 Agent
  + 1 个统一 Tool Gateway
  + 1 个 RAG / 知识库
  + 1 套状态、审计、评测、降级体系
```

技术建议为 Spring AI 2.x + LangGraph4j，工具协议考虑 MCP，Agent 间协议考虑 A2A，观测考虑 Langfuse/Phoenix/ClickHouse，评测建议 200–500 条 golden cases；同时给出 Python Phase-0 参考实现。

蓝图本身也明确了两条收敛建议：

- “首期 4–5 个子 Agent 足够”，不是越多越好；
- `HandoffAgent` “也可以不做成独立 Agent，而由 Supervisor 的转人工策略实现；首期建议作为策略，不增加一个 LLM 调用”。

### 需要决策的问题

| # | 问题 | 备选 |
|---|---|---|
| 1 | 在线 Agent 运行时放在哪里 | Java 单体内 / 独立 Java 服务 / Python 服务 |
| 2 | 模型访问层用什么 | Spring AI / 继续自建 `LlmClient` / LangChain4j |
| 3 | 编排用什么 | LangGraph4j 状态图 / 继续两阶段 if-else / Python LangGraph |
| 4 | 工具怎么执行 | 自建 Tool Gateway / 原生 `tool_calls` / MCP |
| 5 | 子 Agent 怎么切 | 4 个 LLM Agent + Handoff 策略 / 5 个 LLM Agent / 全自治 swarm |
| 6 | Trace 落哪里 | PostgreSQL 自建表 / Langfuse / ClickHouse |
| 7 | 是否引入 Python | 不引入在线服务 / 独立 Python Agent 服务 |

## 决策（Decision）

**一句话**：V3.1 在现有 Java 模块化单体内，用 Spring AI 2.0.2 做模型抽象与调用观测，用 LangGraph4j 1.9.8 做显式状态图与检查点，用自建 Tool Gateway 做唯一执行面；采用 **4 个 LLM 子 Agent + 确定性 Handoff 策略**；业务 trace 落 PostgreSQL；**不引入 Python 在线服务、不依赖原生 `tool_calls`、不引入 Langfuse/ClickHouse**。

| 决策点 | 选择 | 明确不选 |
|---|---|---|
| 部署形态 | 继续 Java 模块化单体，Agent 运行时在 `shang-customerservice` 进程内 | 独立 Agent 微服务、Python 在线服务 |
| 模型抽象 | Spring AI 2.0.2（`spring-ai-openai` / `spring-ai-client-chat`） | 业务代码散落 `RestClient`；`LlmClient` 保留为回退与对照 |
| 编排 | LangGraph4j 1.9.8（`langgraph4j-core` / `langgraph4j-spring-ai` / `langgraph4j-postgres-saver`） | Python LangGraph 在线运行、继续堆 if-else 作为长期方案 |
| 工具执行 | 自建 Tool Gateway（`ToolRegistry` + `ToolExecutor` + `ToolDefinition`） | 原生 `tool_calls` 作为生产路径、模型直连 HTTP/SQL、MCP 热路径 |
| 子 Agent | 4 个 LLM 子 Agent + 1 个 Handoff 策略节点 | 5 个 LLM 子 Agent、全自治多 Agent 集群 |
| Trace | PostgreSQL 自建表（`agent_run` / `agent_step` / `llm_call` / `tool_call` / `retrieval` / `guardrail_event`） | Langfuse、ClickHouse、专用 LLM 观测平台作为必需依赖 |
| Agent 间协议 | 进程内 Java 接口 / 状态图节点 | MCP/A2A 作为热路径 |
| 迁移方式 | 特性开关，V3.0 两阶段可随时回退 | 一次性替换 |

### 1. 部署形态：继续 Java 模块化单体

Agent 运行时落在 `shang-customerservice`，与订单、商品、身份同进程：

- 子 Agent 只依赖 `shang-commerce-api` / `shang-identity-api` / 本上下文服务，不 import 实现模块；
- 工具调用是进程内方法调用，不增加网络跳数；
- 复用现有 Spring 事务、MyBatis-Plus、Flyway、Redis、MinIO、SkyWalking；
- 不新增独立网关进程、不新增 Python 运行时、不拆微服务。

**理由**：当前体量与团队规模下，独立 Agent 服务带来的部署、鉴权、观测、数据一致性成本大于收益；模块化单体的边界已由 Maven 模块与 `*-api` 在编译期约束（`ADR-0004`）。未来若出现跨团队复用、独立扩缩容或跨语言需求，再按 `ADR-0001` 的触发条件拆分。

**Python 的边界**：Python 仅允许用于离线评测、数据分析或未来的训练/蒸馏工具链，不得进入在线请求链路；本仓库当前不存在 `agent_service/`，蓝图中的 Python Phase-0 只作为设计参考。

### 2. Spring AI 2.0.2 的职责边界

Spring AI 只负责**模型访问与调用观测**，不负责业务执行：

| 职责 | 说明 |
|---|---|
| 模型抽象 | 统一 `ChatModel` / `ChatClient`，对接 OpenAI 兼容 chat-completions；`base-url` / `api-key` / `model` / `temperature` 继续走 `.env` + `identity.system_config` 覆盖 |
| 流式解析 | 解析 SSE 增量，区分正文与 `reasoning_content`；spike 已验证 `AssistantMessage.getMetadata().get("reasoningContent")` 可拿到思考增量 |
| 调用观测 | 采集 model、usage（输入/输出 token）、finishReason、耗时；写入自建 `llm_call` 表 |
| 不负责 | 工具权限、幂等、确认闸门、业务状态机、审计——这些由 Tool Gateway 与编排层负责 |

保留现有 `LlmClient` 作为**回退与对照实现**：V3.0 两阶段链路继续使用它；V3.1 新链路通过适配器使用 Spring AI。两者指向同一组配置，避免迁移期出现两套密钥/两套模型配置。若 Spring AI 在真实网关上出现 `reasoning_content` 元数据缺失或流式解析差异，适配器可回退到 `LlmClient` 的解析逻辑，不阻塞主链路。

### 3. LangGraph4j 1.9.8 的职责边界

LangGraph4j 负责**显式状态图、节点编排与检查点**。V3.1 的 Supervisor 不是第 5 个 LLM Agent，而是“状态图 + 路由/预算/转人工规则”：

```text
START
  → route（规则优先；未命中时一次轻量 LLM 分类）
  → shopping_guide | order | after_sale | knowledge
  → tool / retrieval（只读可并行；写操作只生成计划）
  → need_confirmation（WRITE_HIGH，等待用户确认）
  → answer（流式生成）
  → handoff（策略节点：转人工 / 结束）
  → END
```

图状态至少包含：

| 字段 | 用途 |
|---|---|
| `traceId` / `sessionId` / `customerId` / `channel` | 全链路关联；`customerId` 由后端登录态注入，不由模型参数决定 |
| `intent` / `slots` | 路由结果与槽位；缺槽位进入追问 |
| `activeAgent` | 当前子 Agent |
| `toolResults` | 工具结果摘要（不保存完整大对象） |
| `pendingActionId` | 高风险写操作的待确认动作（见 `ADR-0008`） |
| `budget` | 最大工具步数、截止时间、token 预算 |
| `status` | `OK` / `NEED_USER_INPUT` / `NEED_CONFIRMATION` / `HANDOFF` / `FAILED` |

检查点策略：

- 使用 `langgraph4j-postgres-saver` 持久化每个 super-step 的状态摘要与 `checkpoint_id`；
- 不逐 token 落库，不在 checkpoint 中保存完整 prompt/response；
- 失败恢复时从最近检查点继续，但**写操作不得因恢复而重复执行**（由 `ADR-0008` 的幂等键与状态机保证）；
- 检查点不可用或图执行异常时，降级回 V3.0 两阶段链路，保证会话不中断。

预算沿用既有约束：读工具 1s、写工具 3s、单次任务最多 3 次工具调用、最多 1 次重规划；总预算按渠道配置。

### 4. 自建 Tool Gateway 是唯一执行面

V3.1 的工具执行只允许经过 `com.shangpivot.customerservice.agent.tool` 下的网关：

| 组件 | 职责 |
|---|---|
| `ToolDefinition` | 工具名、描述、风险级别、允许调用的子 Agent、超时、是否要求幂等、简化 JSON Schema |
| `ToolExecutor` | 工具实现；只允许调用 `commerce-api` / `identity-api` / 本上下文服务 |
| `ToolRegistry` | 注册与查找、子 Agent 白名单、必填参数校验、超时、写操作确认闸门、trace 埋点 |
| `ToolResult` | 成功/失败、结构化数据、摘要、错误码、错误信息 |
| `ToolContext` | `customerId` / `sessionId` / `runId` / `traceId` / `agent` / `confirmed` / `idempotencyKey`；`customerId` 必须由后端注入 |

硬约束：

1. 模型不能拼 SQL、不能任意发 HTTP、不能直接访问数据库；只能提出工具计划。
2. 工具必须声明风险级别；`WRITE_HIGH` 未确认时 `ToolRegistry` 直接返回 `CONFIRMATION_REQUIRED`。
3. 工具必须有超时、错误码、审计字段；写工具必须有幂等键。
4. 工具调用记录写 `tool_call` 表；记录失败不影响主流程。

**原生 `tool_calls` 不是生产路径**：不把 Spring AI 的 `ToolCallback` 直接绑定到业务工具，不让模型输出直接触发执行。模型仍输出结构化意图/槽位/工具计划 JSON，由编排层与 Tool Gateway 校验后执行。这样做的代价是多一次模型往返与 JSON 容错解析，收益是彻底隔离网关对 `tool_calls` 的不稳定支持。

**MCP/A2A 的定位**：MCP 只用于可选的外部工具暴露或内部 QA（如 ZenTao），不进入客服热路径；A2A 在当前单体内部没有使用场景，不引入。两者都不作为 V3.1 的依赖。

### 5. 4 个 LLM 子 Agent + Handoff 策略

| 子 Agent | 职责 | 主要工具 | 写操作 |
|---|---|---|---|
| `ShoppingGuideAgent` | 商品搜索、对比、推荐、库存/时效 | `searchProducts`、`getProduct`、`getStock`、`getDeliveryEta` | 无 |
| `OrderAgent` | 订单状态、物流、取消/确认收货 | `getOrder`、`getLogistics`、`cancelOrder`、`confirmReceive` | 取消/确认收货为 `WRITE_HIGH` 计划 |
| `AfterSaleAgent` | 退换货政策、售后单、工单 | `searchKnowledge`、`getAfterSalePolicy`、`createAfterSale`、`createTicket` | 售后/工单为 `WRITE_HIGH` 计划 |
| `KnowledgeAgent` | FAQ、政策、活动规则 RAG | `searchKnowledge`、`getDoc` | 无 |
| `Handoff`（策略节点） | 转人工判定、排队、会话摘要 | `escalateToHuman`、`createTicket` | 会话状态写；不作为独立 LLM Agent |

每个子 Agent 必须声明契约：

```text
name
description
allowed_intents
allowed_tools
max_tool_steps
timeout_ms
requires_confirmation
fallback_policy
```

输出契约（对齐 `AgentStatus`）：

```text
status: OK | NEED_USER_INPUT | NEED_CONFIRMATION | HANDOFF | FAILED
answer
citations
tool_calls
confidence
handoff_reason
usage { tokens, latencyMs, model }
```

边界规则：

- 不跨域：订单 Agent 不直接查商品表，售后 Agent 不直接改订单状态；
- 不递归：子 Agent 不调用其他子 Agent，跨域由状态图编排；
- 不无限循环：最多 3 次工具调用、1 次重规划；
- 不越权：工具网关按 `customerId + role + channel` 过滤；
- 不静默失败：失败必须返回可解释原因，必要时转人工。

**为什么不是 5 个 LLM 调用**：Handoff 是确定性策略（显式请求、投诉/负面情绪、连续 N 轮未解决、低置信度、高危场景、工具连续失败），用规则即可判定；把它做成第 5 个 LLM Agent 会增加一次模型往返、成本与不确定性，且难以保证“该转人工时一定转”。蓝图本身也允许把 Handoff 作为策略，本 ADR 采纳该收敛建议。

路由优先复用 `IntentRouter` 的规则短路；规则未命中时才用一次轻量 LLM 分类。该分类调用属于路由开销，不计入“4 个子 Agent”的角色数量。

### 6. Trace 与评测：PostgreSQL 优先，不引入 Langfuse/ClickHouse

V3.1 的业务 trace 落 `customerservice` schema，表结构见 `V25__v31_agent_runtime.sql`：

| 表 | 内容 |
|---|---|
| `agent_run` | 一次用户任务：`trace_id`、`session_id`、`customer_id`、`intent`、`agent`、`status`、token、耗时、模型、prompt 版本 |
| `agent_step` | 图节点/子 Agent 步骤：`node`、`agent`、`status`、输入/输出摘要、`latency_ms`、`checkpoint_id` |
| `llm_call` | 一次模型调用：model、prompt 版本、输入/输出 token、finishReason、耗时、请求/响应摘要 |
| `tool_call` | 一次工具调用：工具名、参数、结果、状态、耗时、重试次数、幂等键、错误码 |
| `retrieval` | 一次 RAG 检索：query、topK、docIds、scores、耗时、是否命中、后端（vector/lexical/context） |
| `guardrail_event` | 注入/敏感输出/越权/限流等护栏事件 |
| `pending_action` | 高风险写操作待确认动作（详见 `ADR-0008`） |
| `feedback` | 点赞/点踩/转人工/解决等反馈（P1） |

关联键：

```text
HTTP traceId
  └─ agent_run.id（runId）
      ├─ agent_step.id（stepId）
      ├─ llm_call.id
      ├─ tool_call.id
      └─ retrieval.id
```

写入策略：

- 元数据 100% 采集；完整 prompt/response 默认只存摘要或采样，错误请求可存脱敏后的全文；
- 手机号、地址、身份证、支付信息、token 落库前脱敏；
- 不逐 token 落库；流式结束后聚合为一条 `llm_call`；
- 大字段可放 MinIO，DB 只存对象 key；V3.1 先使用摘要字段，不引入新存储；
- 预留保留期与清理任务，避免 `agent_run` / `llm_call` 无限增长。

**不引入 Langfuse/ClickHouse 的理由**：当前数据量与团队规模下，多一套中间件意味着部署、备份、权限、PII 合规与值班成本；PostgreSQL 已能支撑按 `run_id` / `session_id` / `status` 查询与看板聚合。SkyWalking 继续作为基础设施链路观测，不作为 Agent 业务 trace 的唯一来源。未来出现高频事件分析、prompt 版本管理或人工标注需求时，再评估 Langfuse/ClickHouse，不在 V3.1 预置。

评测策略：

- 复用已落地的 20 条 Golden cases（`AgentEvalReportTest` / `GoldenDataset`），先覆盖高频与高风险路径；
- 确定性断言作为硬门禁（工具正确性、卡片、关键词、禁止模式、审计字段），LLM Judge 作为趋势信号，不作为唯一门禁；
- 用例从失败 trace、线上会话、工单中逐步扩充，不追求首期 200–500 条；
- 任何 prompt / 模型 / 工具变更都必须跑同一套评测。

### 7. 迁移与回退

- 新老编排共存：`ChatOrchestrator`（V3.0 两阶段）保留，新增图运行时实现同一入口接口；
- 特性开关：`agent.runtime.mode=two-phase|graph`，默认 `two-phase`，按会话/白名单灰度；
- 数据库只新增表，不改 V3.0 已有表；回滚不需要数据迁移；
- 切换门禁：20 条 Golden 确定性断言通过、写操作安全测试通过、首字/全链路时延不劣化超过预算、故障降级演练通过；
- 任一节点异常、检查点不可用、模型不可用 → 自动回退 V3.0 链路并记录 `guardrail_event` / 审计。

## 后果（Consequences）

### 正面

1. **部署与运维简单**：一个进程、一套配置、一套鉴权、一套日志；不新增 Python/Agent 微服务。
2. **复用既有资产**：Spring 事务、MyBatis-Plus、Flyway、Redis、MinIO、SkyWalking 全部复用；PostgreSQL 同时承担业务、向量与 trace。
3. **执行面可控**：Tool Gateway 统一权限、超时、风险、幂等与审计；模型无法绕过网关直接写库或发 HTTP。
4. **可恢复、可解释**：显式状态机与检查点让多轮任务可恢复；每个节点/模型/工具/检索都有 trace。
5. **成本可预测**：4 个 LLM 角色 + 规则路由，避免 5 个 LLM 调用与全自治 swarm 的不可控成本。
6. **可回退**：V3.0 两阶段链路仍在，特性开关可快速切回。

### 负面

1. **依赖较新**：Spring AI 2.0.2 与 Boot 4.1.1 同代但生态仍在演进；LangGraph4j 社区规模小于 Python LangGraph，API 可能变化。
2. **图编排复杂度**：状态定义、检查点、节点边界、失败恢复比两阶段 if-else 更难调试；需要团队学习成本。
3. **多一次模型往返**：不依赖原生 `tool_calls`，意图/计划与生成分离，延迟高于理想化的原生函数调用。
4. **PostgreSQL trace 膨胀**：高频调用下 `llm_call` / `tool_call` 增长快，需要采样、归档与保留期策略。
5. **没有 Langfuse UI**：prompt 版本对比、人工标注、实验管理需要自建管理页或后续接入，V3.1 只保证可查询。
6. **4 个固定角色扩展性有限**：新增领域需要新增 Agent 契约与工具白名单，不能像动态 swarm 一样自由组合。

### 风险与控制

| 风险 | 影响 | 控制 |
|---|---|---|
| Spring AI / LangGraph4j 版本升级不兼容 | 编译或运行时失败 | BOM 锁定版本；spike 测试纳入 CI；升级前必须重跑兼容性与 20 条 Golden |
| `reasoning_content` 元数据键或流式格式变化 | 思考流丢失或正文解析错误 | 适配器隔离；spike 契约测试；回退 `LlmClient` 解析 |
| 检查点 schema 与 Flyway 冲突 | 启动失败或恢复异常 | `langgraph4j-postgres-saver` 表独立命名/独立迁移；启动时校验；不可用时降级无检查点模式 |
| 图状态过大 | 内存/DB 膨胀 | checkpoint 只存摘要与槽位；完整内容放 `llm_call` 摘要或 MinIO |
| trace 含 PII / 大字段 | 合规与性能风险 | 落库前脱敏、截断、采样；敏感字段不落明文；保留期清理 |
| 图运行时故障导致客服不可用 | 主链路中断 | 特性开关 + 自动回退 V3.0；节点级超时与预算；失败转规则/人工 |
| 子 Agent 误路由 | 答非所问或越权 | 规则优先路由；工具白名单；Golden 评测；低置信度转人工 |
| 团队绕过网关直接写业务 | 审计缺口、重复执行 | 代码评审红线；`ToolRegistry` 唯一入口；ArchUnit/静态检查后续补充 |
| 未来有人重新引入 Python 在线服务 | 双栈运维 | 本 ADR 明确在线链路只用 Java；Python 仅离线工具链 |

## 验证（Verification）

### 已执行：兼容性 spike（2026-11-07）

命令：

```bash
cd backend
./mvnw -q -pl shang-customerservice -am \
  -Dtest=AgentRuntimeSpikeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

| # | 验证假设 | 方法 | 结果 |
|---|---|---|---|
| 1 | Spring AI 2.0.2 可在 Boot 4.1.1 / Java 21 下构造并流式调用 OpenAI 兼容端点 | `OpenAiChatModel.builder()` 指向 JDK `HttpServer` 模拟的 `/v1/chat/completions` SSE；不启动 Spring 上下文 | 通过：正文增量可聚合为“你好” |
| 2 | `reasoning_content` 可被解析并保留 | 模拟两段 `reasoning_content`（“先想” + “一下”）与两段 `content`；读取 `AssistantMessage` metadata | 通过：metadata 中聚合为“先想一下” |
| 3 | LangGraph4j 1.9.8 可建图、执行并使用 checkpoint | `StateGraph` 两节点 `start → next`，`CompileConfig` 配 `MemorySaver`，`invoke` 返回最终状态 | 通过：输出 `hello-world` |
| 4 | 条件边可用 | `route` 节点按 `status` 分支到 `answer`/`reject` | 通过：命中 `answer` |

测试文件：`backend/shang-customerservice/src/test/java/com/shangpivot/customerservice/agent/AgentRuntimeSpikeTest.java`

spike 边界（不夸大为生产验收）：

- 使用本地 mock HTTP Server，不访问真实网关；
- 未验证真实网关的长连接、限流、错误码与 `reasoning_content` 边界情况；
- 使用 `MemorySaver`，未验证 Postgres saver 与重启恢复（spike 局限；生产已接入 `PostgresSaver`，见实现状态）；
- 未启动 Spring 上下文，未验证自动配置、MCP starter、pgvector 方言与 Flyway 交互；
- 未覆盖 4 个子 Agent、Tool Gateway 全链路、写操作确认与 trace 落库。

### 待验证（进入 V3.1 实现前/后）

| 验证项 | 通过标准 |
|---|---|
| 真实网关流式 | `reasoning` 与 `delta` 分流正确；首字在预算内；异常可降级 |
| Postgres checkpoint | 进程重启后可从检查点恢复；写操作不重复执行 |
| 图运行时回退 | 任意节点异常可回退 V3.0 两阶段，会话不中断 |
| Golden 对等 | 20 条用例确定性断言不低于 V3.0 基线 |
| 工具网关 | 子 Agent 越权、缺参、超时、`WRITE_HIGH` 未确认均被拒绝并留痕 |
| 写操作安全 | 见 `ADR-0008` 的并发/幂等/过期/越权测试矩阵 |
| 延迟与成本 | 首字、P95、单任务 token 成本在渠道预算内 |

## 实现状态（2026-11-07）

> 本节是撰写时的仓库快照。V3.1 代码正在并行落地，最终以合并后的代码与 CI 结果为准；未完成项不得在汇报中写成已上线。

| 项 | 状态 | 证据 |
|---|---|---|
| Spring AI 2.0.2 / LangGraph4j 1.9.8 BOM 与依赖 | 已加入工作区 | `backend/pom.xml`、`backend/shang-customerservice/pom.xml` |
| 子 Agent 名称与状态契约 | 已加入工作区 | `agent/contract/AgentName.java`、`AgentStatus.java` |
| Tool Gateway（定义/执行/风险/结果/上下文/注册表）与工具实现 | 已加入工作区；工具实现已覆盖商品、订单、物流、知识、加购、取消、确认收货、售后、工单 | `agent/tool/*.java`、`agent/tool/impl/*.java` |
| 兼容性 spike | 已通过（3/3，含条件边） | `AgentRuntimeSpikeTest` |
| V25 trace / pending_action 迁移 | 已在本地 PostgreSQL 17 执行并验证；8 张表 + JSONB 往返通过 | `V25__v31_agent_runtime.sql`、`AgentTracePersistenceTest` |
| LangGraph4j 图编排 | 已实现 `route → execute → answer` + 条件边 + Handoff；默认入口仍为 legacy，可用 `AGENT_ENGINE=graph` 切换 | `agent/graph/AgentGraphOrchestrator.java` |
| 检查点 | 已接入 `PostgresSaver`：每个 run 独立 thread（`run-<runId>-<uuid>`）落 `LG4JThread`/`LG4JCheckpoint`，每轮 4 个 checkpoint（START/route/execute/answer）；DB 异常由 `ResilientCheckpointSaver` 降级为“只丢 checkpoint”；`agent.checkpoint=memory` 可回退 | `AgentCheckpointConfig`、`ResilientCheckpointSaver`、`AgentGraphOrchestrator` |
| Spring AI 生产适配器 / 配置接线 | 已实现 `SpringAiLlmClient`（`AGENT_LLM_CLIENT=spring-ai` 切换；默认 `legacy`），流式 reasoning diff 单测通过 | `llm/SpringAiLlmClient.java`、`SpringAiLlmClientTest` |
| trace 写入服务 | `AgentTraceService` + `CsAgentTraceController` 已接线 | `agent/trace/AgentTraceService.java` |
| 4 个角色提示词 | 已实现（`AgentPromptFactory`）；Handoff 策略节点已实现（`HandoffPolicy`） | `agent/graph/AgentPromptFactory.java`、`HandoffPolicy.java` |
| 写操作待确认服务 | 已实现（创建/复用、CAS 确认、取消、TTL、失败保持 CONFIRMED 可重试）；确认/取消 HTTP 接口已接线 | 见 `ADR-0008` |
| 管理端 UI | Trace 列表/详情 + Agent 指标卡已实现并通过前端构建 | `CsAgentRunsView.vue`、`CsStatsView.vue` |
| 图编排集成测试 | 已通过（8/8，真实 PG + mock commerce/LLM） | `AgentGraphIntegrationTest` |
| Graph Golden 对等 | 100/100 确定性断言通过（20 核心 + 80 扩容；sources/cards/answer/forbid/效率/购物车；未跑裁判） | `GraphGoldenIntegrationTest` + `GoldenDataset` |
| 写操作安全矩阵 | 10/10（未确认不写、确认一次、重复确认幂等、取消/过期不写、越权拒绝、缺参/越权工具拒绝） | `AgentWriteSafetyIntegrationTest` |
| V3.0 两阶段编排 | 仍是当前 Controller 注入的默认路径 | `ChatController` 注入 `ChatOrchestrator` |

> 本 ADR 的“Accepted”表示架构决策已拍板，不表示上述未实现项已经上线。V3.1 的切换必须以验证门禁为准。

## 增量状态（2026-11-07 21:55）

- **Postgres checkpoint 已上线**：生产启动日志 `agent checkpoint: postgres (LG4JThread/LG4JCheckpoint)`；真实消息验证 1 个 thread / 4 个 checkpoint；`public.lg4jthread`、`public.lg4jcheckpoint`（LangGraph4j 1.9.8 v1.1 schema）。
- **线程隔离**：每条用户消息使用独立 `run-<runId>-<uuid>`，避免 LangGraph4j 按 threadId 续跑上一轮；`runId` 前缀便于从 `agent_run` 反查。
- **失败降级**：`ResilientCheckpointSaver` 包装 `PostgresSaver`，put/get/release 异常只记 warn 并继续对话；启动时建表失败回退 `MemorySaver`。
- **配置**：`AGENT_CHECKPOINT=postgres|memory`（默认 postgres），已写入 `.env.example`。
- **数据集**：Graph Golden 扩到 100 条（`EVAL_DATASET_VERSION=v31-100`）；生产 100/100 确定性断言通过（2026-11-07 23:03）；发现并修复“商品名数字被当购买数量”的路由 bug。
- **评测隔离**：`POST /api/chat/sessions?fresh=true` 结束当前进行中会话再新建；`AgentHarness` 每个用例强制新会话，避免跨用例卡片/指代污染，以及转人工后的 QUEUED 会话吞掉后续用例。
- **路由/选品修正**：对比与二选一（“哪个吸力好/哪个便宜”）强制走商品检索而非单商品 SPECS；`resolveProduct` 允许检索关键词直接命中商品名（修复“来三个收纳盒”因品牌前缀匹配失败）；多规格商品未指定规格时返回 `sku:` 来源并反问，不擅自加购。
- **保留策略**：LG4J checkpoint 当前不自动清理；按每轮 4 行估算，后续可按 `saved_at` 增加月度归档/清理任务（未实现）。

> **生产状态（2026-11-07 21:10）**：生产 `.env` 已设 `AGENT_ENGINE=graph`（`AGENT_LLM_CLIENT=legacy`）；legacy 20/20、graph 50/50 确定性通过；高风险写演练（取消/确认收货/售后/取消动作）已用真实测试订单验证；管理端 Agent 轨迹持续出数。代码默认仍为 `legacy`，回滚只需改环境变量并重启。
