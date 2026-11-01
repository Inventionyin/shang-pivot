# customer-service 限界上下文 — 详细需求设计

> 依据 `ADR-0001`，本上下文为模块化单体中的智能客服模块（package `com.shang.customerservice`，schema `customerservice`）。融合 Agent（大模型 + RAG）。术语见 [`docs/UBIQUITOUS_LANGUAGE.md`](../UBIQUITOUS_LANGUAGE.md)。
> **文档位置说明**：本上下文即本工程的 AI 产品层（V3.0 智能客服），故设计文档归入 `docs/03-ai/`；identity / commerce 的技术细节见 `docs/04-architecture/`（`v1.0/`、`v2.0/`、`adr/`）。三者仍为 ADR-0001 定义的平行上下文。V3.0 PRD 产出后按 `docs/01-requirements/_template-PRD.md` 生成，本文档为其 §15（AI 功能需求）与开发设计的技术基线。
>
> **版本纪律**：本文属于 **V3.0**，尚未实现。V1.0 / V2.0 的文档、原型与代码中**不得出现**本上下文的概念（客服入口、会话、坐席、工单、知识库等）——包括配置项与「占位入口」。落地 V3.0 时按 `00-product/roadmap.md` 新建 `PRD-V3.0-*.md`、`02-design/v3.0/`、`04-architecture/v3.0/`。

## 1. 边界与职责

- **在范围内**：会话、消息、坐席、技能组、快捷回复、知识库（文档+向量化）、工单、Agent 编排（意图/RAG/函数调用/转人工）、安全护栏、审计、AI 配置、看板。
- **不在范围内**：订单/物流/库存数据归 commerce；坐席身份归 identity。
- **集成**：坐席 = identity 的 `AdminUser`（坐席角色）；Agent 函数调用走 **commerce 模块 API**（进程内），不直读 commerce 表；LLM 走第三方大模型 API（通义/文心/GLM，后台可配）。

## 2. 核心实体与字段

| 实体 | 关键字段 |
|---|---|
| `ChatSession` 会话 | id, customerId, clientType, status(进行中-ai/排队中/进行中-人工/已结束), seatId, startedAt, endedAt, summary |
| `Message` 消息 | id, sessionId, role(user/assistant/tool), contentType(text/image/card), content, metadata, toolCalls(json), createdAt |
| `Ticket` 客服工单 | id, sessionId, customerId, type, title, status(待处理/处理中/已解决), seatId, sla, createdAt |
| `Seat` 坐席 | id, adminUserId, status(在线/忙/离线), maxConcurrent |
| `SkillGroup` 技能组 | id, name |
| `seat_skill_group` | seatId, skillGroupId |
| `QuickReplyGroup` / `QuickReply` | 分组 / id, groupId, title, content, sort |
| `KnowledgeDoc` 知识文档 | id, title, category, content, status(草稿/发布), version, vectorStatus(待向量化/已向量化), updatedAt |
| `AuditLog` 审计 | id, sessionId, event, risk, detail, createdAt |
| 向量索引 | 存 PgVector（`customerservice` schema 的向量表），非业务实体 |

## 3. 会话状态机（与 UBIQUITOUS_LANGUAGE 一致）

```
进行中(智能客服) →(转人工)→ 排队中 →(坐席接入)→ 进行中(人工) →(结束)→ 已结束
进行中(智能客服) →(解决)→ 已结束
```

## 4. Agent 编排流程

```
用户消息 → 入库 → [安全层: PII脱敏/越狱检测]
  → [意图识别] (售前/订单/售后/物流/闲聊)
  → 路由:
      售前/闲聊 → RAG检索知识库 → LLM生成(带引用)
      订单/物流/售后 → LLM函数调用(工具集) → 执行(调commerce API) → LLM汇总
  → [安全层: 输出过滤/引用/免责]
  → 返回 → 必要时[转人工]
  → 全程审计
```

### 4.1 函数调用工具集（调 commerce 模块 API）
| 工具 | 入参 | 用途 |
|---|---|---|
| `queryOrder` | customerId, orderNo | 查订单状态/金额 |
| `queryLogistics` | orderNo | 查物流轨迹 |
| `queryStock` | skuId | 查可售库存 |
| `createAfterSale` | orderId, type, reason | 发起售后 |
| `recommendProduct` | keyword, categoryId? | 商品推荐 |

### 4.2 RAG
- 知识库来源：售后政策/FAQ/商品问答/活动规则（后台 `KnowledgeDoc` 维护）。
- 流程：文档→切片（按段落/固定长度）→embedding→向量库（PgVector）→检索 topK→（可选重排）→拼入 prompt。
- 重建：后台改文档触发增量/全量向量化（`vectorStatus` 置待向量化→异步处理→已向量化）。

### 4.3 转人工触发
- 用户主动（"转人工"）；
- 关键词（投诉/情绪负面）；
- 连续 N 轮未解决；
- LLM 置信度低/降级失败；
- 高危（退款纠纷）。

## 5. 安全护栏

- **输入**：PII 脱敏（手机号/地址）、提示注入检测。
- **输出**：敏感词过滤、引用来源、免责声明、不透露内部 prompt/系统提示。
- **降级**：LLM 失败→规则/FAQ 兜底。
- **审计**：全量会话 + 工具调用记录入 `AuditLog`。

## 6. 接口设计（REST，`{code,msg,data}`）

### C 端
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/sessions` | 开场，返回 sessionId |
| POST | `/chat/sessions/{id}/messages` | 发消息（SSE/流式返回） |
| GET | `/chat/sessions` | 我的会话列表 |
| POST | `/chat/sessions/{id}/escalate` | 转人工 |
| GET | `/chat/quick-questions` | 推荐问 |

### 后台（坐席）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/customer-service/sessions?status=` | 待接入/进行中会话 |
| POST | `/admin/customer-service/sessions/{id}/claim` | 接入 |
| POST | `/admin/customer-service/sessions/{id}/messages` | 坐席发消息 |
| POST | `/admin/customer-service/sessions/{id}/transfer` | 转技能组 |
| GET/POST | `/admin/customer-service/tickets` | 工单流转 |
| GET/POST/PUT/DELETE | `/admin/customer-service/knowledge-docs` | 知识库 CRUD |
| POST | `/admin/customer-service/knowledge-docs/rebuild` | 重建向量索引 |
| GET/POST/PUT/DELETE | `/admin/customer-service/quick-replies` | 快捷回复 |
| GET/PUT | `/admin/customer-service/seats` | 坐席在线状态 |
| GET | `/admin/customer-service/stats` | 看板（接入量/转人工率/满意度） |
| GET/PUT | `/admin/customer-service/ai-config` | LLM 配置（provider/key/model/temp/systemPrompt） |

## 7. 跨上下文集成

- 从 identity 取 `customerId`/`adminUserId` 与权限（安全上下文）。
- 函数调用走 commerce 模块 API（§4.1），**不直读 commerce 表**。
- 坐席在线状态本上下文维护（`Seat`），但其身份（`adminUserId`）指向 identity。

## 8. 数据模型（schema `customerservice`）

`chat_session`、`message`、`ticket`、`seat`、`skill_group`、`seat_skill_group`、`quick_reply_group`、`quick_reply`、`knowledge_doc`、`audit_log`、`ai_config`（或并入系统配置）。
向量索引：PgVector 扩展表 `knowledge_chunk`（id, docId, chunkText, embedding vector, createdAt）。

## 9. 规则与不变式

- 顾客会话与坐席权限隔离：顾客只能看自己的会话；坐席只能看分配/待接入的。
- 转人工后 AI 不再自动应答，除非坐席退回 AI。
- 知识文档发布前不参与检索（仅 `status=发布` 且 `vectorStatus=已向量化` 的切片可检索）。
- 工单 SLA 计时从转人工开始；超时升级（Demo：仅告警）。

## 10. 非功能

- LLM 首字 < 2s（流式）；失败降级。
- 会话消息异步持久化，不阻塞流式返回。
- Token/耗时/转人工率埋点入审计与看板。
- 向量化重建异步执行，不阻塞后台编辑。
