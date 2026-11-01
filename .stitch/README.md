# Stitch Skills — 本地原型设计工作流（无 MCP）

## 已安装配置

- **技能位置**：`.agents/skills/`（16 个技能，完整复制自 `google-labs-code/stitch-skills`）
- **工作目录**：`.stitch/`（`DESIGN.md` / `SITE.md` / 抽取的 HTML 都放这里）
- **Stitch MCP**：未配置（按需求：本地使用，不上传 Stitch）

> 这 16 个技能同时也在全局 `~/.agents/skills/` 中可用，本项目内的副本用于让项目自包含、版本固定。

## 本项目技术栈

- 前端：Vue 3 + Vite + Pinia + Vue Router + TypeScript
- 后端：Java / Maven

---

## ✅ 不需要 MCP、现在就能本地用的技能

| 技能 | 用途 | 本项目适用性 |
| --- | --- | --- |
| `taste-design` | 生成高质感 `DESIGN.md`（颜色/字体/间距/组件/动效规范） | ✅ 框架无关 |
| `extract-design-md` | 从现有前端源码反向提取设计系统到 `DESIGN.md` | ✅ 明确支持 Vue |
| `enhance-prompt` | 把模糊想法加工成结构化、含 UI/UX 关键词的精炼提示词 | ✅ 纯文本工作 |
| `extract-static-html` | 快照运行中的 dev server 成自包含 HTML（Puppeteer） | ✅ 对 Vite dev server 可用 |

## ⛔ 需要 Stitch MCP（未配置，当前不可用）

| 技能 | 为何需要 MCP |
| --- | --- |
| `generate-design` | 调 `generate_screen_from_text` 等，AI 生成设计稿发生在 Stitch 服务端 |
| `code-to-design` / `upload-to-stitch` / `manage-design-system` | 都要把本地内容上传到 Stitch |
| `design-md` | 通过 MCP 拉取 Stitch 项目的 screen 元数据 |
| `stitch-loop` | 依赖 MCP 自动循环生成多页站点 |

> 关键点：**核心的「用 AI 生成设计稿」功能（`generate-design`）必须有 Stitch MCP 才能在本地调用**。不配 MCP 时，生成环节需手动到网页完成（见路径 A）。

---

## 本地做原型的两条路径（不配 MCP）

### 路径 A：仍用 Stitch 生成，但免 MCP（网页手动操作）

1. 本地跑 `taste-design` 生成 `.stitch/DESIGN.md`
2. 跑 `enhance-prompt` 把页面想法加工成精炼提示词
3. 手动到 <https://stitch.withgoogle.com> 网页：粘贴提示词 + 上传 `DESIGN.md` → 生成
4. 下载生成的 HTML 到 `.stitch/`
5. 在 Vue 前端里手动转成组件

> MCP 只是把第 3 步的「粘贴上传」自动化；网页手动操作完全等价，不需要任何凭证或 MCP 配置。

### 路径 B：完全不用 Stitch（纯本地）

1. 跑 `taste-design` 产出 `.stitch/DESIGN.md` 作为设计规范
2. 直接在 `shang-admin-web/src` 里按规范手写 Vue 组件实现原型
3. 用 `extract-static-html` 随时快照当前页面做归档/对比

---

## ⚠️ React 系构建技能（对本 Vue 项目不直接适用）

`react-components` / `react-vite-dashboard` / `react-native` / `shadcn-ui` 面向 React。
- `shadcn-ui` 在 Vue 下可改用社区的 `shadcn-vue`（技能里的组件设计指导仍可参考，安装命令需替换为 `npx shadcn-vue@latest add ...`）。

---

## 快速开始（直接对我说即可）

- 「用 taste-design 生成 `.stitch/DESIGN.md`，调性是 ___」→ 产出设计系统规范
- 「用 enhance-prompt 加工这个页面想法：___」→ 产出精炼提示词
- 「用 extract-design-md 扫描 `shang-admin-web/src` 提取设计系统」→ 从现有代码反推 DESIGN.md
- 「启动 dev server 后用 extract-static-html 快照某页面」→ 产出自包含 HTML
