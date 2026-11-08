# Agent 评测公开来源与改写记录（v32-200）

> 原则：公开数据只提供**场景/问法/轨迹模式**；商品、价格、库存、政策、订单号全部改写为尚品汇当前事实。仓库不保存第三方原始对话全文。

## 1. 已核实来源

| 来源 | 可迁移内容 | 许可 | 本次用途 |
|---|---|---|---|
| [τ-bench / τ²-bench](https://github.com/sierra-research/tau2-bench) | retail 域 policy、tools、`evaluation_criteria.actions`、模拟 DB 状态；50 任务/域/语言 | MIT | 订单取消/退货/换货、缺槽位、需确认、越权等轨迹模式 |
| [ToolBench](https://github.com/OpenBMB/ToolBench) | 126,486 指令、469,585 API 调用、solution path（推理+工具+结果） | Apache-2.0（README 注明研究/教育） | 多工具编排、工具选错恢复、参数缺失 |
| [prompts.chat / Awesome ChatGPT Prompts](https://github.com/f/awesome-chatgpt-prompts) | 通用提示词库（prompts.csv / PROMPTS.md） | prompt 内容 CC0；代码 MIT | 角色/约束/输出格式写法；无电商 expected，不能直接当测试集 |
| [LangGPT](https://github.com/langgptai/LangGPT) | Profile/Goal/Skills/Rules/Workflow 模板 | Apache-2.0 | 统一提示词变体的结构 |
| GitHub“电商客服 prompt”检索 | 5 个相关仓库（Agent-Observability-Demo、dave-agent、ecommerce-multimodal-intent-classification、promptfoo-redteam、Product-Assistant） | 页面未显示许可 | 仅作场景灵感，未复制原文 |
| JDDC 中文电商客服对话 | 本次未核实到可访问官方仓库（原地址 404，GitHub 搜索 0 结果） | 未知 | 暂未使用；如有授权副本可另开批次 |

## 2. 本批 200 条的来源映射

| 用例区间 | 来源 | 说明 |
|---|---|---|
| E01–E20 | 原有核心集 | 不修改期望值 |
| E21–E100 | 既有目录/知识/多轮集 | 不修改期望值 |
| E101–E130 | 本店 30 个在售 SPU | 规格/库存/价格，事实来自 `commerce.spu/sku` |
| E131–E150 | `customerservice.knowledge_doc` | 政策条款变体 |
| E151–E165 | 真实订单场景 + 手写 | 无订单/单号不存在/转人工/闲聊 |
| E166–E185 | τ-bench/ToolBench 轨迹模式 + 手写 | 对比、预算、加购、跨品类、目录外 |
| E186–E195 | 手写对抗集 | 注入/PII/越权/外部平台/不存在订单 |
| E196–E200 | prompts.chat/LangGPT 结构 + 本店事实 | 同义问法、大小写、长描述、预算表达 |

## 3. 改写检查清单

- [x] 商品名/价格/库存与当前目录一致；
- [x] 政策关键词来自当前知识库原文；
- [x] 订单号使用不存在的测试号，不触碰真实客户；
- [x] 公开来源只保留 URL/许可/场景类型，不保留原始对话；
- [x] 每条至少一个确定性断言；
- [x] 新增 case 默认 `judge=false`，仅 E01–E20 继续跑 Judge。
