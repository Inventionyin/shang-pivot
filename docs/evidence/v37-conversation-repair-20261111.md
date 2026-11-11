# V3.7 多轮澄清与答案质量闭环（2026-11-11）

## 1. 线上问题

用户截图链路（生产 `AGENT_ENGINE=graph`）：

1. 推荐「美的风冷冰箱」后，用户输入口语/口误「就喝一个人吧」→ 被判售前检索，无新结果后走 `previousProducts` 模板；模板无条件以“当前会话没有可操作的商品”开头，却又列出上文商品和卡片，自相矛盾。
2. 用户输入「确认 就买这个」→ 判成加购，但 `extractProductRef` 得空；`isImplicitReference` 不认“确认 + 买 + 这个 + 吧”组合，关键词变成“确认就买”，检索 0 命中后返回全部上文卡片作为歧义候选；只有 1 款却问“您指的是哪一款？”。
3. 用户输入「1」→ 无裸序号规则，LLM 判成闲聊，返回身份欢迎语，上下文丢失。

同类问题：澄清后的“1/第二个/选2/好的/行/嗯”接不住；“推荐 MacBook”后回“1”被误当成 SKU 序号；“把洗碗机加进购物车”可能拿上文冰箱凑数。

## 2. 根因

| 位置 | 问题 |
|---|---|
| `IntentRouter` | 指代确认只认严格句式；无“澄清后承接”状态；裸序号无规则 |
| `ProductReferenceResolver.isImplicitReference` | 未剥离“确认/好的/行/就/买/这个/吧”等填充词 |
| `ProductReferenceResolver` 兜底 | 无信号时返回 `Ambiguous(全部上文卡片)`，单候选也反问 |
| `FactAnswerRenderer.previousProducts` | 文案与卡片事实矛盾 |
| `pickSku` | 裸数字无条件当 SKU 序号，无法区分“选商品 1”与“选规格 1” |
| `AnswerGuard` | 不校验“说没有商品但本轮有卡”“未加购却说已加购” |

## 3. 修复

- 新增 `ConversationFollowUpService`：从上一轮助手文本识别“商品序号 / 规格序号 / 是-否确认 / 推荐列表【商品N】”，把裸序号和确认词确定性映射到商品名，再走原有加购/规格链路；graph 与 legacy 共用。
- `ProductReferenceResolver`：
  - 口语确认归一化（`确认 就买这个`、`就买这个吧`、`好的，就要这个`、`行，就它`、`嗯`）；
  - 单候选直接 `Unique`；无信号不再返回全部上文卡片，改为 `NotFound`；
  - 明确点名目录外商品（洗碗机）时不拿上文无关卡片凑数；
  - “有什么规格/有货吗/多少钱”短追问承接上文。
- `FactAnswerRenderer`：`previousProducts` 改为“本轮没有检索到新的商品。刚才为您推荐过…”；新增 `productNotInCatalog`、`confirmProduct`。
- `askWhichProduct`：单候选改为“您是要加购《X》吗？回复确认/不是”，多候选才列序号；无候选不再回填 `latestCards`。
- `pickSku`：裸数字只有在上一条助手消息确实在问规格时才当 SKU 序号，避免“选商品 1”被顺手选成 SKU1。
- `AnswerGuard`：新增“有卡片却说没有商品”“无加购成功事实却说已加购”两类回退。

## 4. 质量闭环

- C 端点踩：`POST /api/chat/messages/{id}/feedback`，原因 6 类；V33 `customerservice.message_feedback`（唯一索引 message_id+customer_id）。
- 管理端：`/cs-quality`（点赞率/点踩率/原因分布/每日趋势/最近差评）。
- 差评与多轮失败回流：`mine_failure_candidates.py` 纳入 `negative_feedback`、`clarify_without_followup`、`no_context_claim`，人工复核后进入 `conversation-repair`。
- 回归集：`ConversationRepairDataset` 40 条（C01–C40），覆盖口语确认、裸序号、规格序号、单候选、目录外与截图完整链路；`GraphGoldenIntegrationTest#graphMatchesConversationRepairDeterministicAssertions` 每次 CI 跑；导出 `conversation-repair-40.jsonl` 供平台。

## 5. 验证

本地（PostgreSQL 17.10，`AGENT_ENGINE=graph`，mock LLM）：

```
GraphGoldenIntegrationTest
- graphMatchesGoldenDeterministicAssertions                  PASS
- graphMatchesReferenceCartDeterministicAssertions           PASS
- graphGoldenSurvivesBadLlm                                  PASS
- graphMatchesConversationRepairDeterministicAssertions      PASS (40/40)
```

单元测试：

```
ProductReferenceResolverTest      PASS（含口语确认/单候选/目录外/多候选）
ConversationFollowUpServiceTest   PASS（7）
AnswerGuardTest                   PASS（含 2 条新增一致性校验）
MessageFeedbackServiceTest        PASS（4）
```

前端：`shang-mall`、`shang-admin-web` `npm run type-check` 均通过。

CI：`.github/workflows/ci.yml` 新增 `backend-integration` job（Postgres pgvector + Redis + Rabbit），运行 Graph Golden / Reference Cart / Conversation Repair 并导出 JSONL。

## 6. 生产验收口径

- 截图链路：推荐 → “确认 就买这个” → 生成加购确认卡，不出现“哪一款”单候选反问，不重置问候。
- 歧义后“1/第二个/选2”能选中对应商品。
- 多规格商品“1”先问规格，下一轮“2/规格1”选 SKU。
- “把洗碗机加进购物车”返回“没有找到…具体商品”，不展示冰箱卡片。
- 管理端 `/cs-quality` 可见点踩数据；点踩后 trace 可挖成候选。
