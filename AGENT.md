# AGENT.md — shang-pivot 产品开发规范

> 本文件是本工程所有 agent 会话的统一入口。它定义从需求到功能实现的**门控流水线**，规定每阶段用哪个 skill、产出什么、何时算完成。两套 skill 家族（Matt Pocock SDD 工程套件 + 设计套件 stitch/frontend-design/ui-ux-pro-max）在这里汇成一条线。

## 项目上下文

- **管理端前端**：Vue 3 + Vite + Pinia + Vue Router + TypeScript → `shang-admin-web/`
- **商城前端（C 端）**：Vue 3 + Vite + Pinia + Vue Router + Vant → `shang-mall/`
- **后端**：Java / Maven（Spring Boot）→ `backend/`，启动模块 `shang-server`
- **Issue tracker**：本地 markdown，`.scratch/<feature>/`（非 git 仓库、无远端）。详见 `docs/agents/issue-tracker.md`
- **设计工作目录**：`.stitch/`（`DESIGN.md`、抽取的 HTML）
- **Stitch MCP**：未配置。设计稿生成走 Stitch 网页手动（见原型阶段"路径 A"）
- **术语**：以 `CONTEXT.md` / `docs/UBIQUITOUS_LANGUAGE.md` 为准，不造同义词

## 两条路径

- **全流程**（新功能 / 跨模块改动 / 引入新概念）：走下文六阶段门控，每阶段产出物 + 出口门槛达标才进下一阶段。
- **轻路径**（单文件 bug 修复 / 样式微调 / 用户已说清的小改）：直接 `implement` → `code-review`（必要时先 `qa`）。不过 to-spec/to-tickets。
- **判断**：改动是否引入新领域概念 / 跨多个模块 / 需要设计决策？是 → 全流程；否 → 轻路径。

## 流水线总览

| 阶段 | 主 skill | 产出物 | 出口门槛 |
|---|---|---|---|
| 1 需求 | `research`·`to-questionnaire`·`domain-modeling`·`ubiquitous-language`·`to-spec` | `.scratch/<feature>/spec.md` + `docs/UBIQUITOUS_LANGUAGE.md` + `CONTEXT.md` | 术语无歧义、阻塞决策有答案或已发问卷、spec 已发布 |
| 2 原型 | `prototype`·(Stitch)`enhance-prompt`+`taste-design`+网页生成 | 一次性原型 / `.stitch/*.html` | 回答了那个设计/状态问题 |
| 3 页面设计规范 | `ui-ux-pro-max`·`frontend-design`·`taste-design`/`extract-design-md` | `.stitch/DESIGN.md` | 含色/字/间距/组件/动效，无 AI 套路 |
| 4 开发设计 | `design-an-interface`·`codebase-design`·`request-refactor-plan` | 接口方案 + 模块边界 + ADR | 接口形状定、接缝明确、ADR 记录 |
| 5 测试设计 | `qa`·`tdd` | 测试范围 + 边界用例 + bug issue | 范围与边界用例确定 |
| 6 功能实现 | `to-tickets`·`implement`·`tdd`·`code-review` | tracer-bullet tickets + 代码 + review | tickets 全绿、code-review 通过 |

## 阶段细则

### 1 需求
先调研、定术语、把定不了的决策做成问卷，最后用 `to-spec` 把对话综合成 spec 发布到 `.scratch/<feature>/spec.md`。

- `research`：对模糊问题查可信主源，结论存 `docs/`。
- `to-questionnaire`：遇到自己定不了的决策，做成问卷给别人填。
- `ubiquitous-language`：抽术语表 → `docs/UBIQUITOUS_LANGUAGE.md`，标歧义、提规范词。
- `domain-modeling`：固化领域模型、记 ADR；`CONTEXT.md` 懒创建。
- `to-spec`：把已讨论的综合成 spec，发布到 tracker（`.scratch/<feature>/spec.md`）。
- **出口门槛**：术语无歧义（ubiquitous-language 无 flag）；阻塞决策有答案或已发问卷；spec 已发布并被看过一眼。

### 2 原型
用一次性原型验证状态模型 / 逻辑 / UI 走向。**原型是用来扔的**，别直接交付。

- `prototype`：建 throwaway 原型回答"这个状态机/逻辑对不对""UI 该长啥样"。
- 设计稿生成（Stitch）：`enhance-prompt` 把想法加工成精炼提示词 → `taste-design` 产 `DESIGN.md`（或直接复用第 3 阶段的）→ 手动到 stitch.withgoogle.com 网页粘贴生成 → 下载 HTML 到 `.stitch/`。MCP 未配，不能本地自动生成。
- **出口门槛**：原型回答了那个设计问题；若否，回到需求。

### 3 页面设计规范
产出 `.stitch/DESIGN.md` 作为后续所有页面的设计契约。

- `ui-ux-pro-max`：查样式/配色/字体配对/UX 准则/动效（明确支持 Vue）。
- `frontend-design`：避免模板化默认，做有辨识度的视觉。
- `taste-design`：生成高质感 `DESIGN.md`（色/字/间距/组件/动效，反 AI 套路）。
- `extract-design-md`：已有 Vue 源码时，反向提取现有设计系统到 `DESIGN.md`（审计/迁移用）。
- **出口门槛**：`DESIGN.md` 含配色+字体+间距+组件+动效，无 AI 紫/霓虹等套路色。

### 4 开发设计
定接口形状和模块边界（"deep modules"），再动代码。

- `design-an-interface`：用并行子代理生成多个 radically different 接口方案（design it twice）。
- `codebase-design`：找深化点、定接缝、提可测性。
- `request-refactor-plan`：要重构时，做细粒度提交的重构计划，发为 issue。
- **出口门槛**：接口形状选定、模块接缝明确、与 ADR 不冲突（冲突则显式标出）。

### 5 测试设计
先定测什么、边界在哪，再写实现（TDD 友好）。

- `qa`：交互式 QA，对话里报 bug，自动发 issue 到 `.scratch/<feature>/issues/`。
- `tdd`：先写失败测试再实现。
- **出口门槛**：测试范围明确；关键边界用例（空/极值/并发/失败路径）已列。

### 6 功能实现
把 spec + DESIGN.md + 接口决策拆成 tracer-bullet tickets，逐个实现 + 评审。

- `to-tickets`：把 spec 拆成带阻塞边的 tracer-bullet tickets，发布到 `.scratch/<feature>/issues/NN-*.md`。
- `implement`：按 spec/tickets 实现。
- `tdd`：实现时红-绿-重构。
- `code-review`：实现后过 review。
- **出口门槛**：所有 tickets 状态 resolved；code-review 通过；`CONTEXT.md`/ADR 按需更新。

## 何时停 / 何时回退

- 任一阶段出口门槛没过：**停在该阶段**，不进下一步。
- 原型/设计推翻了需求假设：回到阶段 1 更新 spec。
- 实现中发现接口设计错：回到阶段 4。

## Skill 速查（按调用场景）

- "查清楚 X 是什么" → `research`
- "这个决策我定不了" → `to-questionnaire`
- "定术语 / DDD 词汇表" → `ubiquitous-language` / `domain-modeling`
- "把刚才讨论的写成 spec 发出去" → `to-spec`
- "做个一次性原型验一下" → `prototype`
- "生成设计系统规范 DESIGN.md" → `taste-design`（新建）/ `extract-design-md`（从现有代码）
- "加工 UI 提示词" → `enhance-prompt`
- "选配色/字体/动效/UX 准则" → `ui-ux-pro-max`
- "做有辨识度的视觉" → `frontend-design`
- "设计模块接口 / 找接缝" → `design-an-interface` / `codebase-design`
- "规划重构" → `request-refactor-plan`
- "报 bug / 交互式 QA" → `qa`
- "TDD" → `tdd`
- "拆 spec 成 tickets" → `to-tickets`
- "按 spec/tickets 实现" → `implement`
- "代码评审" → `code-review`
- "超大工作规划成决策票" → `wayfinder`
- "issue 流转状态机" → `triage`

## Agent skills 配置（bootstrap 产出，由 setup-matt-pocock-skills 流程生成）

- **Issue tracker**：本地 markdown，`.scratch/<feature>/`。详见 `docs/agents/issue-tracker.md`
- **Triage labels**：默认五角色（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 `docs/agents/triage-labels.md`
- **Domain docs**：single-context，根 `CONTEXT.md` + `docs/04-architecture/adr/`。详见 `docs/agents/domain.md`

## 约定

- **产出物位置**：`.scratch/<feature>/`（spec/tickets/issues）、`.stitch/`（DESIGN.md/HTML）、`CONTEXT.md`/`docs/04-architecture/adr/`（领域）、`docs/agents/`（配置）。**产品文档**见 `docs/README.md`（路线图 / PRD / 体验 / ADR / 数据与接口 / 开发与运维 / 统一语言）；新 PRD 按 `docs/01-requirements/_template-PRD.md` 生成
- **版本纪律（红线）**：时间线单向 —— **V1 文档不含 V2 内容；V2 可含 V1（基线）但不含 V3/V4**。写「不做」必须标目标版本。新版本按 `docs/00-product/roadmap.md` 的规则建 `PRD-V<版本>` + `02-design/v<版本>/` + `04-architecture/v<版本>/`
- **文档产物要能重放**：原型改完跑 `node .stitch/原型/render-shots.mjs` 重渲全部截图；后台原型用 `__NAV_VERSION__='v1'|'v2'` 裁剪侧栏，V1 页面截图里不允许出现后续版本菜单
- **数据也算产物**：种子数据必须能演示该版宣称的功能（如宣称有库存预警 → 种子里要有低库存行；宣称可删草稿 → 种子里要有草稿商品）
- **命名一致**：菜单名 = 页面标题（不造同义词）
- **术语统一**：用 `CONTEXT.md` / `docs/UBIQUITOUS_LANGUAGE.md` 里的；新概念缺失 → 信号，记给 `domain-modeling`
- **ADR 冲突**：输出与现有 ADR 冲突 → 显式标出，不静默覆盖
- **前端栈注意**：React 系 skill（`react-components`/`react-vite-dashboard`/`react-native`/`shadcn-ui`）不直接适用 Vue；shadcn 用社区 `shadcn-vue`
- **Stitch 生成**：走网页手动（无 MCP）；要本地一键生成需另配 Stitch MCP
