# ADR-0008：Agent 写操作的分级确认与幂等执行

- **状态**：Accepted
- **日期**：2026-11-07
- **决策范围**：V3.1 Agent 写操作（智能客服）
- **相关**：`ADR-0005`、`ADR-0007`；`backend/shang-server/src/main/resources/db/migration/V25__v31_agent_runtime.sql`；`docs/06-baseline/02-业务规则与状态机.md`；`docs/03-ai/agent/README.md`

## 背景（Context）

V3.1 的 Agent 开始触达有副作用的写操作：

| 操作 | 目标 | 风险 |
|---|---|---|
| 加入购物车 | `commerce` 购物车 | 低：不产生订单、可编辑/删除；但重复加购会改变数量 |
| 取消订单 | `commerce` 订单状态 `PENDING_PAY → CLOSED`，释放库存 | 高：状态不可逆（需重新下单）；并发/重放可能二次释放库存（OT-07 对重复取消有幂等返回，但读-改-写无行锁，见 NEG-S12） |
| 确认收货 | `commerce` 订单状态 `PENDING_RECEIVE → COMPLETED` | 高：不可逆，影响售后窗口与结算 |
| 发起售后 | `commerce` 售后单 | 高：生成业务单据，重复提交会产生重复售后 |
| 修改收货地址 | `commerce` 地址/订单 | 高：影响履约；已发货订单不可改 |

写操作的风险不只来自模型幻觉，还来自工程链路：

1. **参数幻觉**：模型可能编造订单号、SKU、数量、售后原因，或把“取消订单”理解成“取消售后”。
2. **重复执行**：用户双击确认、SSE 断线重连、前端重试、后端超时重试、图检查点恢复，都可能让同一意图执行多次。
3. **状态过期**：用户看到确认卡后去喝了杯水，订单可能已支付/已发货，确认时的前置条件已变化。
4. **越权**：模型参数里出现别人的 `orderId` / `customerId`，或提示注入诱导 Agent 操作他人订单。
5. **静默失败**：工具超时后模型仍生成“已为您取消”的假成功话术。

V3.0 的现状：

- 对话内加购在 `ChatOrchestrator.addCartFacts()` 中**直接调用** `CartMutationApi.addToCart()`，没有统一 Tool Gateway、没有幂等键、没有二次确认；
- 取消订单、确认收货、发起售后在 V3.0 默认链路中尚未由 Agent 执行，只做查询/知识回答；V3.1 图编排已能生成待确认计划，但尚未默认启用；
- 已有 `ToolRisk`（`READ` / `WRITE_LOW` / `WRITE_HIGH`）与 `ToolRegistry` 的 `WRITE_HIGH` 未确认拒绝逻辑（工作区代码）；
- `V25__v31_agent_runtime.sql` 已起草 `pending_action`、`tool_call` 及唯一索引。

既有领域约束（`docs/06-baseline/02-业务规则与状态机.md`）：

- 取消仅允许 `PENDING_PAY`；重复取消 `CLOSED` 订单直接返回，不二次释放库存（OT-07）；
- 售后仅允许 `PENDING_RECEIVE` / `COMPLETED`，且同订单不能有进行中的售后（AS-04/AS-05）；
- 当前支付/发货/售后审核存在“读-改-写”无行锁的已知并发风险（NEG-S12），因此 Agent 层不能把“领域里应该不会重复”当作唯一防线。

## 决策（Decision）

**一句话**：按风险分级。`WRITE_LOW`（加购）默认执行但必须带幂等键；`WRITE_HIGH`（取消/确认收货/售后/地址）必须走 `pending_action` + 用户显式确认 + 幂等键 + 审计；模型只提出计划，永远不直接执行。

### 1. 风险分级

| 等级 | 典型工具 | 默认行为 | 用户确认 | 幂等键 | 审计 |
|---|---|---|---|---|---|
| `READ` | `searchProducts`、`getOrder`、`getLogistics`、`searchKnowledge` | 直接执行 | 不需要 | 不需要 | 记录 `tool_call`（可选） |
| `WRITE_LOW` | `addToCart` | 意图/槽位确认后默认执行；数量上限、SKU 必须明确 | 不额外弹确认卡 | **必须** | `tool_call` + `audit_log` |
| `WRITE_HIGH` | `cancelOrder`、`confirmReceive`、`createAfterSale`、`updateAddress` | 只生成计划，不执行 | **必须**（结构化确认） | **必须** | `pending_action` + `tool_call` + `audit_log` |

说明：

- `WRITE_LOW` 的“默认执行”不等于“无校验”：必须通过子 Agent 白名单、参数 Schema、顾客归属、SKU 在售/库存、数量上限（V3.1 默认单次 ≤ 99，且不超过库存）；SKU 或数量不明确时返回 `NEED_USER_INPUT`，不得猜测。
- `WRITE_HIGH` 不允许“模型说已确认”“聊天文本里的‘确认’直接触发”。V3.1 只接受绑定到具体 `pending_actionId` 的结构化确认（前端确认按钮 → 确认接口）；聊天文本中的“确认”只有在被前端显式绑定到该 action 且经过同一套校验后才可视为确认。
- 转人工/创建工单属于会话状态写，走 `Handoff` 策略与既有转人工接口，不纳入商品写操作分级；但同样要审计。
- 自动退款、自动修改已支付订单金额、自动删除数据等操作**不在 V3.1 范围**；高风险争议直接转人工。

### 2. 写操作协议：计划 → 确认 → 执行

```text
模型/子 Agent
  │  只输出计划：{tool, args, summary, risk}
  ▼
Tool Gateway 预检
  │  工具存在？子 Agent 允许？参数 Schema？顾客归属？业务前置条件？
  ▼
创建 pending_action（PENDING，写 idempotency_key + expires_at）
  │  返回 NEED_CONFIRMATION + actionId + 展示摘要
  ▼
用户确认（结构化接口）
  │  原子 CAS：PENDING → CONFIRMED；再次校验归属与前置条件
  ▼
执行工具（带同一个 idempotency_key）
  │  成功 → EXECUTED；失败 → 保持 CONFIRMED 并记录错误，可同键重试至 TTL
  ▼
审计 + 结果回传（回答/卡片）
```

关键点：

1. **模型不执行**：模型输出只是计划；`ToolGateway.execute()` 只接受经过校验的计划，不接受模型直接调用业务服务。
2. **确认是独立动作**：确认接口属于用户，不属于模型；模型无法伪造 `confirmed=true`。
3. **参数冻结**：确认时执行的是 `pending_action.args_json` 中冻结的参数，不重新让模型生成参数；若用户要改参数，取消旧 action，重新规划。
4. **执行前再校验**：订单状态、售后资格、地址可改性在确认时和执行前各校验一次；状态变化则取消并解释，不“带病执行”。
5. **可解释**：确认卡展示工具、目标、关键参数、影响（如“取消后库存释放，订单不可恢复”）、过期时间。

### 3. 状态机

```text
                   用户确认（CAS）
        PENDING ──────────────────► CONFIRMED ──执行成功──► EXECUTED
           │                            │
           │ 用户取消                    │ 前置条件变化 / 重试耗尽
           ▼                            ▼
       CANCELLED                    CANCELLED
           ▲
           │ TTL 到期
        EXPIRED ◄──── PENDING / CONFIRMED（未执行成功且 TTL 到期）
```

状态迁移表：

| 当前状态 | 事件 | 下一状态 | 约束 |
|---|---|---|---|
| `PENDING` | 用户确认 | `CONFIRMED` | `UPDATE ... WHERE id=? AND status='PENDING' AND expires_at > now()`；只有一个请求能赢 |
| `PENDING` | 用户取消 | `CANCELLED` | 只有发起会话的顾客可取消 |
| `PENDING` | TTL 到期 | `EXPIRED` | 执行前也检查 `expires_at`，不依赖定时任务 |
| `CONFIRMED` | 工具执行成功 | `EXECUTED` | 写入 `result_json`，终态 |
| `CONFIRMED` | 工具执行失败 | `CONFIRMED` | 记录 `result_json.error`；TTL 内可用同一幂等键重试 |
| `CONFIRMED` | 执行前校验失败 / 重试耗尽 | `CANCELLED` | 记录原因，不执行 |
| `CONFIRMED` | TTL 到期 | `EXPIRED` | 未执行成功，终态 |

- 终态：`EXECUTED`、`CANCELLED`、`EXPIRED`；终态不可回退到 `PENDING`/`CONFIRMED`。
- 状态值以 Java 枚举 + 状态迁移服务为准；V25 当前用列注释约定，未加 DB `CHECK`，后续迁移可补。
- 默认 TTL 10 分钟（可配置）。过期清理可懒执行（读取/确认/执行时校验）加定时任务兜底。

**实现对齐（2026-11-07）**：已按推荐方案收敛——工具失败保持 `CONFIRMED`，错误写入 `result_json.error`，同一动作在 TTL 内可重试；不引入第六态。`cancel()` 仍只允许 `PENDING`，已 `CONFIRMED` 的动作由 TTL 到期转 `EXPIRED`，避免执行中取消产生竞态。

### 4. 幂等与 exactly-once

#### 4.1 幂等键

- 由**服务端**生成，模型不可传入、不可覆盖；必须绑定 `sessionId + toolName + 参数`，可附加 `runId` / UUID：

```text
示例：wa:{sessionId}:{runId}:{toolName}:{uuid}
当前实现：SHA-256(sessionId|toolName|args) 截断为 64 位十六进制
```

- `pending_action.idempotency_key` 唯一（`uk_cs_pending_idem`）；
- `tool_call.idempotency_key` 唯一，但只约束 `RUNNING` / `SUCCESS` 状态（`uk_cs_tool_idem` 部分唯一索引），允许失败后同键重试；
- 执行工具时把同一个 key 写入 `ToolContext`，工具实现/下游接口必须携带；V3.1 先在网关层做结果缓存与去重。

#### 4.2 并发与重放

1. **创建**：同一意图重复提交时，先按 `pending_action.idempotency_key` 查已有记录；已存在则返回原 action，不新建。
2. **确认**：用原子 CAS 抢占：

```sql
UPDATE customerservice.pending_action
SET status = 'CONFIRMED', updated_at = now()
WHERE id = :id
  AND status = 'PENDING'
  AND expires_at > now();
```

只有 `affected rows = 1` 的请求可以执行；其余请求返回当前状态（已确认/已执行/已取消/已过期），不重复调用工具。

3. **执行**：`ToolRegistry` 执行后写 `tool_call`（带幂等键与结果摘要）；当前实现不强制先插 `RUNNING` 占位。高风险 exactly-once 由 `pending_action` 的 CAS 保证；`tool_call` 部分唯一索引保留给后续“执行前去重/对账”扩展。
4. **成功**：`tool_call` 记为 `SUCCESS`，`pending_action` 更新为 `EXECUTED`，写入 `result_json`。
5. **失败**：`tool_call` 记为 `FAILED`/`TIMEOUT`；`pending_action` 保持 `CONFIRMED`，错误写入 `result_json.error`；同一 key 在 TTL 内可重试，但只允许 `result_json.error` 存在的 CONFIRMED 动作重试（避免并发双执行）。
6. **重启/检查点恢复**：恢复逻辑只读取 `pending_action` 状态；`EXECUTED` 直接回放结果，`CONFIRMED` 不自动重放，必须由用户重新确认或显式重试。

#### 4.3 exactly-once 的边界

- **应用层成功路径**：唯一键 + CAS，保证同一 `pending_action` 最多产生一次成功副作用；`tool_call` 记录用于审计与后续对账。
- **领域层兜底**：取消订单本身幂等（重复取消 `CLOSED` 直接返回）；售后有“同订单无进行中售后”校验；确认收货有状态校验。即使网关在“副作用已发生、成功未落库”的窄窗口崩溃，重试也会被领域状态拒绝或返回同一结果。
- **加购的窄窗口**：购物车是低风险、可编辑的累加操作；若在“已加购、结果未落库”窗口崩溃，重试可能多加一次。V3.1 用网关结果缓存 + 数量上限降低概率，并明确不承诺跨进程崩溃场景的严格 exactly-once；用户可在购物车修正。
- 不把分布式事务引入客服链路；不为了理论上的 exactly-once 让 LLM 请求参与两阶段提交。

### 5. 权限、归属与审计

#### 权限与归属

- `customerId` 必须由后端从登录态（`SecurityContext`）注入 `ToolContext`；模型参数中的 `customerId` 一律忽略或拒绝；
- 所有订单/售后/地址操作在创建 `pending_action` 前校验归属；确认时与执行前再校验一次；
- 子 Agent 只能调用 `ToolDefinition.allowedAgents` 中的工具；`WRITE_HIGH` 未确认时 `ToolRegistry` 返回 `CONFIRMATION_REQUIRED`；
- 坐席/管理端写操作走既有 RBAC，不复用顾客 JWT；
- 提示注入不能替代确认：确认接口是独立的、需要登录态的写接口，不接受模型生成的“确认令牌”。

#### 审计

每次高风险写操作至少留下：

| 审计点 | 内容 |
|---|---|
| 计划 | `run_id`、`session_id`、`customer_id`、`tool_name`、`args_json`（脱敏）、`risk`、`trace_id` |
| 创建 | `pending_action.id`、`idempotency_key`、`expires_at`、模型/prompt 版本 |
| 确认 | 操作人（顾客）、确认时间、确认前后的状态、归属校验结果 |
| 执行 | `tool_call.id`、耗时、结果摘要、错误码、重试次数 |
| 终态 | `EXECUTED` / `CANCELLED` / `EXPIRED`、原因、结果摘要 |

`audit_log` 事件建议：`write_proposed`、`write_confirmed`、`write_executed`、`write_cancelled`、`write_expired`、`write_failed`。手机号、地址、身份证、支付信息落库前脱敏；token/密钥禁止入日志。

### 6. 失败与降级

| 场景 | 行为 |
|---|---|
| 工具超时 | 写工具默认 3s；返回可解释失败，不生成“已成功”话术；`pending_action` 保持 `CONFIRMED`，可同键重试至 TTL |
| 工具业务失败（状态不符/无资格） | 不重试；记录原因；必要时转人工；`pending_action` → `CANCELLED` |
| 确认接口/数据库不可用 | **fail closed**：不执行，提示稍后重试；不允许降级为“直接执行” |
| 用户取消 | `PENDING` → `CANCELLED`；不调用工具 |
| TTL 到期 | `PENDING`/`CONFIRMED` → `EXPIRED`；不执行；用户需重新发起 |
| 前置条件变化 | 执行前重新校验；失败则 `CANCELLED` 并解释（如“订单已发货，无法取消”） |
| 重复确认/双击 | CAS 只有一个赢家；其余请求返回当前状态或已执行结果 |
| SSE 断线重连 | 客户端凭 `actionId` 查询状态；不重新创建 action |
| 模型不可用 | 高风险写操作直接转人工/提示稍后；不做规则直写 |

原则：**宁可拒绝执行，不可静默成功；宁可让用户重新确认，不可复用过期参数。**

### 7. 范围与灰度

- V3.1 首批接入：取消订单、确认收货、发起售后；地址修改放在后续阶段（涉及履约状态更复杂）；
- 加购从 `ChatOrchestrator` 内联调用迁移到 Tool Gateway，补幂等键与审计；迁移完成前保留旧路径，但同一会话不能同时走新旧两条加购路径；
- 通过 `agent.write.mode=off|low|high` 分级开关控制：先只读，再 `WRITE_LOW`，最后 `WRITE_HIGH`；
- 灰度按顾客/会话白名单；出现异常立即关闭写开关，保留查询与转人工；
- 自动退款、批量操作、跨顾客操作永远不开放给 Agent。

## 后果（Consequences）

### 正面

1. **安全边界清晰**：模型只能提议，用户确认后才执行；提示注入无法直接产生写副作用。
2. **可审计**：每次高风险写操作都有计划、确认、执行、结果四段证据，满足客服/合规复盘。
3. **可重放**：幂等键 + CAS + 唯一索引消除大部分重复执行；失败可解释、可重试。
4. **体验可控**：低风险加购不打断用户；高风险操作有明确确认卡与影响说明。
5. **与 Tool Gateway 一致**：权限、超时、风险、幂等、审计统一在网关层，不散落在各个 Agent。

### 负面

1. **多一次交互**：高风险写操作需要“提出计划 → 用户确认 → 执行”，比模型直接调用慢一个用户确认回合。
2. **前端工作量**：需要确认卡、确认/取消接口、状态查询、过期提示；不是纯后端改造。
3. **状态与存储成本**：新增 `pending_action` 表、清理任务、状态迁移代码与测试。
4. **确认疲劳**：如果所有操作都确认，用户会烦；因此必须严格分级，只有高风险才弹卡。
5. **低风险仍需幂等**：加购虽然不弹卡，但同样需要幂等键与审计，不能因为“低风险”就省掉。

### 风险与控制

| 风险 | 影响 | 控制 |
|---|---|---|
| 用户不看确认卡就点确认 | 误操作 | 确认卡突出目标/数量/影响；高风险操作二次文案；TTL 短 |
| 确认后订单状态变化 | 执行失败或错误履约 | 确认时与执行前双重校验；失败取消并解释 |
| 幂等键碰撞 | 错误复用结果 | 服务端生成并绑定 session/tool/args；摘要或随机前缀；唯一索引兜底；碰撞告警 |
| 崩溃窗口导致重复副作用 | 重复加购/重复售后 | 领域状态守卫；`tool_call` RUNNING/SUCCESS 唯一；对账任务（后续） |
| 绕过网关直接写 | 审计缺失、确认失效 | 代码评审红线；Tool Gateway 唯一入口；后续 ArchUnit 约束 |
| 确认接口被越权调用 | 他人订单被操作 | 登录态 + 归属校验 + action 绑定 session/customer；确认接口幂等 |
| 过期 action 堆积 | 表膨胀 | 定时清理/归档；`expires_at` 索引；监控 |
| 确认疲劳导致体验下降 | 用户流失 | 仅 `WRITE_HIGH` 确认；低风险默认执行；文案简洁 |
| V25 状态无 DB CHECK | 非法状态写入 | Java 枚举 + 状态迁移服务统一入口；后续迁移补 CHECK |
| 多实例并发确认 | 双执行 | 数据库 CAS + 唯一索引，不依赖 JVM 锁 |

## 验证（Verification）

### 已具备（2026-11-07）

| 项 | 证据 | 说明 |
|---|---|---|
| 风险枚举 | `agent/tool/ToolRisk.java` | `READ` / `WRITE_LOW` / `WRITE_HIGH` |
| 网关确认闸门 | `agent/tool/ToolRegistry.java` | `WRITE_HIGH` 且 `confirmed=false` 直接返回 `CONFIRMATION_REQUIRED` |
| 待确认表结构 | `V25__v31_agent_runtime.sql` | `pending_action` 字段、`uk_cs_pending_idem` 唯一索引 |
| 工具调用幂等索引 | `V25__v31_agent_runtime.sql` | `uk_cs_tool_idem` 对 `RUNNING` / `SUCCESS` 部分唯一 |
| 待确认动作服务 | `agent/service/PendingActionService.java` | `createOrReuse`、CAS `PENDING → CONFIRMED`、`cancel`、TTL 10 分钟、重复确认回放结果 |
| 写工具实现 | `agent/tool/impl/CancelOrderTool.java` 等 | 取消/确认收货/售后/加购均声明风险、超时、幂等要求 |
| trace 写入 | `agent/trace/AgentTraceService.java` | `pending_action` 之外的 `tool_call` / `agent_run` 埋点 |
| 领域状态约束 | `docs/06-baseline/02-业务规则与状态机.md` | 取消仅 `PENDING_PAY`、重复取消幂等、售后状态/进行中校验 |
| 兼容性 spike | `AgentRuntimeSpikeTest` | 仅证明 Spring AI / LangGraph4j 可用，不证明写链路 |

仍未完成/未验证：

- 确认/取消的 HTTP 接口尚未接线（前端已按 `/api/chat/sessions/{id}/actions/{actionId}/confirm|cancel` 先行约定）；
- `ToolRegistry` 尚未按 `tool_call` 的 `RUNNING/SUCCESS` 唯一索引做“先占位再执行”，目前只做确认闸门与事后记录；
- 加购的幂等键当前按 `runId + args` 生成，跨轮次重试会变化，尚未达到本文要求的“同一意图同键”；
- `CONFIRMED → CANCELLED` 的前置条件变化路径、审计事件、定时过期清理、并发/重放测试均未完成；
- 工具失败当前写 `FAILED`，与五态状态机不一致（见 §3 实现对齐注意）。

### 测试矩阵（实现后必须通过）

| # | 用例 | 期望 |
|---|---|---|
| 1 | 模型只输出计划，未确认 | 不调用写工具；`pending_action=PENDING`；回答为确认卡 |
| 2 | 用户确认一次 | 工具调用恰好 1 次；`EXECUTED`；结果可查询 |
| 3 | 用户重复确认/双击 | CAS 只有一个赢家；其余返回同一状态/结果；工具仍只调用 1 次 |
| 4 | 并发两个确认请求 | 数据库层面只有一个 `CONFIRMED`；无重复副作用 |
| 5 | 用户取消 | `CANCELLED`；工具调用 0 次 |
| 6 | TTL 过期后确认 | `EXPIRED`；工具调用 0 次 |
| 7 | 顾客 A 确认顾客 B 的 action | 拒绝（403/404）；无状态变化；审计记录 |
| 8 | 确认后订单已支付/已发货 | 执行前校验失败 → `CANCELLED`；不调用写工具 |
| 9 | 工具超时 | 不返回“已成功”；`CONFIRMED` 保留错误；同键重试不产生第二条成功记录 |
| 10 | 同一 key 重放成功结果 | 返回缓存结果；不重复调用下游 |
| 11 | 进程重启后恢复 | `EXECUTED` 不重复执行；`CONFIRMED` 不自动执行 |
| 12 | 审计完整性 | 计划/确认/执行/终态四段均可按 `run_id` 追溯；敏感字段已脱敏 |

### 验收标准

- 高风险写操作 **100%** 具备 `pending_action` + 用户确认 + 幂等键 + 审计；
- 并发/重放测试中重复副作用为 **0**；
- 未经确认的 `WRITE_HIGH` 调用 **100%** 返回 `CONFIRMATION_REQUIRED`；
- 模型仅输出计划时，业务状态 **0 变化**；
- 确认接口不可用时 **fail closed**，不存在“降级直写”；
- 过期/取消/失败路径均有可解释话术与审计记录。

> 本 ADR 的“Accepted”表示写操作安全策略已拍板，不表示上述服务与接口已经上线。V3.1 写开关默认关闭，按测试矩阵通过后再逐步开放。
