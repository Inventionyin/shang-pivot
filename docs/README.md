# docs — 读什么

| 你要… | 打开 |
|---|---|
| 术语 / 统一语言 | [`UBIQUITOUS_LANGUAGE.md`](./UBIQUITOUS_LANGUAGE.md) |
| 产品与版本（含版本纪律） | [`00-product/roadmap.md`](./00-product/roadmap.md) |
| V1.0 需求与验收 | [`01-requirements/PRD-V1.0-后台管理.md`](./01-requirements/PRD-V1.0-后台管理.md) |
| V2.0 需求与验收 | [`01-requirements/PRD-V2.0-购物网站.md`](./01-requirements/PRD-V2.0-购物网站.md) |
| V3.0 需求与验收 | [`01-requirements/PRD-V3.0-智能客服.md`](./01-requirements/PRD-V3.0-智能客服.md) |
| 长什么样（设计系统 + 弹窗规范） | [`02-design/设计系统.md`](./02-design/设计系统.md) |
| V1 后台长什么样 | [`v1.0/体验设计.md`](./02-design/v1.0/体验设计.md) |
| V2 购物端长什么样 | [`v2.0/体验设计.md`](./02-design/v2.0/体验设计.md) |
| V3 AI 助手长什么样 | [`v3.0/体验设计.md`](./02-design/v3.0/体验设计.md) |
| V1 怎么实现 | [`v1.0/开发设计.md`](./04-architecture/v1.0/开发设计.md) · [`数据模型`](./04-architecture/v1.0/数据模型.md) · [`接口`](./04-architecture/v1.0/接口设计.md) |
| V2 怎么实现 | [`v2.0/开发设计.md`](./04-architecture/v2.0/开发设计.md) · [`数据模型`](./04-architecture/v2.0/数据模型.md) · [`接口`](./04-architecture/v2.0/接口设计.md) |
| V3 怎么实现 | [`数据模型`](./04-architecture/v3.0/数据模型.md) · [`接口`](./04-architecture/v3.0/接口设计.md) |
| 架构决策 | [`04-architecture/adr/`](./04-architecture/adr/) |
| V3 客服（设计主源，实现基线） | [`03-ai/customer-service-智能客服.md`](./03-ai/customer-service-智能客服.md) |
| 启动 / 编码 / 出口 | [`开发与运维.md`](./开发与运维.md) |
| 当前代码是哪一版 · 数据字典 · 业务规则 · 跨版本验证 | [`06-baseline/README.md`](./06-baseline/README.md) |
| 原型本地预览（VSCode Live Server） | [`VSCode查看本地项目原型.md`](./VSCode查看本地项目原型.md) |
| 演示商品图原件（MinIO 种子数据） | [`05-assets/`](./05-assets/) |

```text
docs/
├── README.md · 开发与运维.md · UBIQUITOUS_LANGUAGE.md（统一语言） · VSCode查看本地项目原型.md（原型预览）
├── 00-product/roadmap.md               版本时间线 + 版本纪律
├── 01-requirements/   PRD-V1.0 · PRD-V2.0 · PRD-V3.0 · 模板 · shots/（截图）
├── 02-design/         设计系统（含弹窗规范） + v1.0/ v2.0/ v3.0/ 体验设计
├── 03-ai/             V3 客服设计主源（实现基线）
├── 04-architecture/   adr · v1.0/ · v2.0/ · v3.0/（各含：开发设计 · 数据模型 · 接口设计）
├── 05-assets/         项目资产：product/（演示商品图原件，MinIO 种子数据）
└── 06-baseline/       版本基线：版本指纹 · 数据字典 · 字段映射 · 业务规则与状态机 · 调用链路（含交互式 HTML 图） · 跨版本验证
```

## 版本纪律（怎么保证不倒着混）

1. **单向时间线**：V1 文档不含 V2 内容；V2 文档可含 V1（基线），但不含 V3/V4 内容。反向混入即串档。
2. **各版自包含**：每份 PRD 独立可读；后版本只引用前版本，不重述。
3. **产物同版**：原型、截图、种子数据都必须能支撑该版宣称的功能。
   - 后台原型用 `__NAV_VERSION__='v1'|'v2'` 裁剪侧栏；
   - 截图由 `.stitch/原型/render-shots.mjs` 一键重放；
   - 种子数据由 Flyway 迁移保证（V1～V7 基线，V8 起为 V2 增量）。
4. **边界写在纸上**：所有「不做」都标出目标版本（如「优惠券（V4.0）」）。

## 边界与优先级

- PRD ＝ What/AC（含交互与弹窗表）· 体验设计 ＝ 观感与手感（截图主源）· ADR/开发设计 ＝ How · 开发与运维 ＝ 怎么跑怎么改 · 统一语言 ＝ 术语唯一来源。
- 冲突裁决：ADR > 其它技术笔记；产品对错以 PRD 为准；命名以 `UBIQUITOUS_LANGUAGE.md` 为准。
- 菜单名与页面标题**同名同义**（如「商品列表」既是侧栏菜单也是页面标题），不造同义词。