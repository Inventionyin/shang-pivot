# api_testcases — 尚品汇（Shang Pivot）接口测试流水线

从元气购（ProjectKu）复用的流水线：**接口/需求文档 → 用例清单 → lint 门禁 → 试点 → 全量上传禅道**。
规范依据：`../docs/测试资料/rule.md`（v2.6）；领域词典：`../docs/测试资料/领域词典.md`。

> **凭据纪律**：账号、密码、Token 一律经环境变量在运行时提供，**不写入任何文件**。

## 目录与事实源

| 路径 | 说明 |
|---|---|
| `test_cases_inventory.md` | **唯一内容源**（14 列，478 条 = 契约 428 + 业务 50） |
| `scripts/build_api_doc.py` | 从后端源码提取 135 个端点 → 生成 `../docs/测试资料/接口文档.md`、`artifacts/endpoint-model.json`、`artifacts/api-doc-diff.md` |
| `scripts/generate_cases.py` | 生成契约用例（正向 / 鉴权 / 参数 / 幂等 / 边界） |
| `scripts/merge_business_cases.py` | 生成 50 条业务链路用例（`BUS-*`）并合并 |
| `scripts/case_lint.py` | 质量门禁（R01–R30 + `--online` 禅道核对） |
| `scripts/lint_selftest.py` | 规则自测（坏例必报 / 好例不报，26 条） |
| `scripts/zentao_sync.py` | 禅道上传 / 修复 / 结果回写 / 建 Bug（**零改动复用**） |

## 尚品汇约定（与元气购的差异，lint 与生成器均已按此适配）

| 项 | 尚品汇 |
|---|---|
| 路径前缀 | 管理端 `/admin/**`、C 端 `/api/**` |
| 成功业务码 | **`0`**（`ErrorCode.SUCCESS`），不是 200 |
| 业务失败族 | `BizException` 无 `@ResponseStatus` → **HTTP 200 + body 数值码** |
| Bean 校验失败 | HTTP 400 + `BAD_REQUEST`（源码仅 6 个端点带 `@Valid`） |
| 未认证 | HTTP 401 + `UNAUTHORIZED` |
| 路由未命中 | HTTP 404 + `NOT_FOUND` |
| 稳定 ID | `METHOD_PATH-NNN` / `BUS-<域>-NNN`；禅道 keyword `API-AUTO,<稳定ID>` |

## 标准流程

```bash
cd shang-pivot-master/api_testcases

# 1) 端点模型 + 接口文档（真值层级：源码 > OpenAPI）
python3 scripts/build_api_doc.py --repo-root .. \
  --openapi ../docs/openapi-v3-api-docs.json \
  --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md

# 2) 生成清单（契约 + 业务）
python3 scripts/generate_cases.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \
  --out test_cases_inventory.md
python3 scripts/merge_business_cases.py --inventory test_cases_inventory.md

# 3) 质量门禁（fail 0 才允许上传）
python3 scripts/lint_selftest.py
python3 scripts/case_lint.py --inventory test_cases_inventory.md \
  --mdout artifacts/lint/lint-report.md --jsonout artifacts/lint/lint-report.json

# 4) 上传禅道（product_id=2 尚品汇；密码只在运行时环境变量中提供）
ZENTAO_BASE_URL=http://YOUR_SERVER_IP:60082 ZENTAO_PRODUCT_ID=2 \
ZENTAO_USER=test ZENTAO_PASSWORD='运行时输入，不要写进文件' \
python3 scripts/zentao_sync.py upload-cases --inventory test_cases_inventory.md

# 5) 在线复核（keyword + 标题一致性）
ZENTAO_BASE_URL=http://YOUR_SERVER_IP:60082 ZENTAO_PRODUCT_ID=2 \
ZENTAO_USER=test ZENTAO_PASSWORD='运行时输入' \
python3 scripts/case_lint.py --inventory test_cases_inventory.md --online
```

## 试点与修复

`upload-cases` 不支持区间，试点用**子集清单**（表头 + 选中的 3–5 行）。
子集天然不满足 R15 覆盖矩阵，故试点用 `--skip-lint`；**全量上传必须走完整门禁**。

```bash
# 试点（C 端读 / 管理端读 / 鉴权 / 含请求体写 各至少一条）
ZENTAO_PRODUCT_ID=2 ZENTAO_USER=test ZENTAO_PASSWORD='运行时输入' \
python3 scripts/zentao_sync.py upload-cases \
  --inventory test_cases_inventory.pilot.md --skip-lint --manifest artifacts/pilot_manifest.json

# 清单变更后同步已上传用例（按 keyword 匹配，支持断点续传与区间）
ZENTAO_PRODUCT_ID=2 ZENTAO_USER=test ZENTAO_PASSWORD='运行时输入' \
python3 scripts/zentao_sync.py repair-cases \
  --inventory test_cases_inventory.md --repair-manifest artifacts/zentao_repair_manifest.json
```

## 禅道字段映射（血泪教训）

禅道 22.6 的「步骤」是动态 `StepsEditor`：提交时必须同时提供
`steps[1]`、`stepType[1]=step`、`expects[1]`，缺 `stepType[1]` 时禅道返回 200 但**静默丢弃步骤**。
本流水线把「预期 HTTP / 预期业务码 / 断言」并入 `expects[1]`；`清理`、`备注` 两列禅道无对应字段，
只保留在清单中（清单是唯一内容源）。成功判定必须是响应体含 `"result":"success"`。

## 本轮产出（2026-10-05）

- 端点 135（源码 ↔ OpenAPI 差异 0）；用例 478（契约 428 + 业务 50）
- 离线 lint fail 0 / 在线 lint fail 0 / 规则自测 26/26
- 禅道「尚品汇」product 2：478/478 上传成功，keyword 全带 `API-AUTO,`
- 详见 `../docs/测试资料/尚品汇流水线复用报告.md`
