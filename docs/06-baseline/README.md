# 06-baseline — 版本基线与跨版本验证

> 目的：让「这个版本到底实现了什么」有一套**可复算、可对版本负责**的文档，并让任意两个版本之间能自动 diff 出「升级会打断什么」。
> 方法论出处：[`requirementTracking.markdown`](../../requirementTracking.markdown)（从代码反推文档的操作手册 v2）。

## 读什么

| 你要… | 打开 | 区 |
|---|---|---|
| 当前代码是哪一版、跑在什么参数上、指纹是多少 | [`00-版本基线.md`](./00-版本基线.md) | 判断区 |
| 数据库每张表每个字段的定义（最终态） | [`01-数据字典.md`](./01-数据字典.md) | **机制区（禁止手改）** |
| 业务规则、状态机、缺口清单 | [`02-业务规则与状态机.md`](./02-业务规则与状态机.md) | 判断区 |
| 怎么验证一个版本、怎么做跨版本 diff、门禁怎么串 | [`03-版本验证与自动化.md`](./03-版本验证与自动化.md) | 判断区 |
| **某个字段在库里叫什么、代码里叫什么、接口里是什么名字和类型** | [`04-字段映射.md`](./04-字段映射.md) | **机制区（禁止手改）** |
| **改一张表/一个服务会影响哪些端点；页面到表怎么走** | [`05-调用链路.md`](./05-调用链路.md) | **机制区（禁止手改）** |
| **交互式追踪（搜索 / 点节点高亮上下游 / 导出 PNG）** | [`05-调用链路图.html`](./05-调用链路图.html) | **机制区（禁止手改）** |

**接口（API）不在本目录**，避免第二套真相：见 [`docs/测试资料/接口文档.md`](../测试资料/接口文档.md)（135 端点，生成器驱动，与 OpenAPI 零差异）。

## 机制区 vs 判断区（决定文档会不会腐坏）

| 区 | 文件 | 谁写 | 能否手改 | 腐坏防线 |
|---|---|---|---|---|
| 机制区 | `01-数据字典.md`、`04-字段映射.md`、`05-调用链路.md`、`05-调用链路图.html`、`artifacts/schema-model.json`、`artifacts/endpoint-model.json`、`artifacts/field-map.json`、`artifacts/call-graph.json`、`docs/测试资料/接口文档.md` | 脚本生成 | **不能** | 重算后 `git diff` 必须为空 |
| 判断区 | `00-版本基线.md`、`02-业务规则与状态机.md`、`03-版本验证与自动化.md` | 人工 | 能（带证据与置信度） | 每条结论带 `file:line @ 版本指纹`；待确认单闭环 |

## 一键复算

```bash
cd api_testcases
python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \
    --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md
python3 scripts/build_db_dict.py --fingerprint "$(git -C .. rev-parse --short HEAD)"
python3 scripts/build_field_map.py --fingerprint "$(git -C .. rev-parse --short HEAD)"
python3 scripts/build_call_graph.py --fingerprint "$(git -C .. rev-parse --short HEAD)"
python3 scripts/build_graph_page.py --fingerprint "$(git -C .. rev-parse --short HEAD)"
python3 scripts/snapshot_baseline.py --label "$(git -C .. rev-parse --short HEAD)"
```

## 打开交互式调用链路图

```bash
# 单文件、零依赖、离线可开：直接双击，或起个静态服务
open docs/06-baseline/05-调用链路图.html
# 想用浏览器标签页方式：
cd docs/06-baseline && python3 -m http.server 8901 --bind 127.0.0.1
#   → http://127.0.0.1:8901/05-调用链路图.html
```

功能：业务域/层/边类型多维过滤、点节点看接口详情（参数、鉴权、服务、涉及表、实现位置）
并高亮上下游、搜索定位、滚轮缩放、拖动平移、折叠侧栏、导出 PNG。

## 跨版本 diff

```bash
cd api_testcases
python3 scripts/baseline_diff.py --old baselines/<旧label> --new artifacts --out /tmp/diff.md
# 退出码 1 = 存在破坏性变更，应阻断发布
```

分级与处置见 [`03-版本验证与自动化.md`](./03-版本验证与自动化.md) §4。

## 版本纪律（沿用 `docs/README.md`）

- **单向时间线**：本目录只描述**已实现**的事实，不写未实现的需求。未实现项一律进 `02` 的「负面清单」与待确认单。
- **不重述既有文档**：PRD/设计/ADR 在 `docs/01-requirements`、`docs/02-design`、`docs/04-architecture`；术语以 `docs/UBIQUITOUS_LANGUAGE(统一语言).md` 为准。本目录只补「实现真值 + 指纹 + 跨版本能力」。
- **冲突裁决**：端点存在性以源码为准（源码 > OpenAPI > 文档）；业务行为以业务确认表为准（确认表 > 接口文档 > 源码）。两域方向相反，已在 `02` §0 显式声明。

## 待确认项

本目录各文档末尾的待确认单汇总了需要业务方/负责人裁决的问题（`00` §9 共 5 条、`02` §11 共 11 条）。**未处置项按严重度计入交付门禁**。
