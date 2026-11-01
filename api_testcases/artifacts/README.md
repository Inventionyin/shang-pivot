# artifacts/ 留痕目录约定

> 原则：**每一轮跑批都要能在事后被复算**。凡"结论"必须有对应产物；凡产物必须能指回产生它的那一轮。
> 出处：元气购对照文档 §6-⑦。

| 目录 / 文件 | 内容 | 产生者 | 被谁消费 |
|---|---|---|---|
| `endpoint-model.json` | 接口事实模型（135 端点：参数是否强制、DTO 字段、业务码） | `scripts/build_api_doc.py` | lint R15/R31–R35、对账 |
| `api-doc-diff.md` | 源码 vs OpenAPI 差异 | `scripts/build_api_doc.py` | 人工复核 |
| `lint-report.{md,json}` | lint 全量违规（含 warn） | `scripts/case_lint.py --mdout/--jsonout` | C1、评审预填 |
| `coverage-gap.json` | 词典反向检查：定义了但没被测的词条 | `scripts/case_lint.py` | 下一轮补用例 |
| `reconcile-report.md` | 三件套对账（接口/需求/词典）差集 | `scripts/reconcile.py` | C2 |
| `exemptions.json` | 对账豁免登记（每条须有理由） | 人工维护 | C2 |
| `known-failures.json` | 失败登记表（每条须带 `defect` + `reason`） | 人工维护 | C3/C4 |
| `review/p0-review.json` | P0 评审台账（`p0_total`/`p0_reviewed`/逐条结论） | `scripts/review_workbench.py` | C6 |
| `review/评审工作单.xlsx` | 评审工作单（机器预填事实，人填结论） | `scripts/review_workbench.py` | 评审人 |
| `automation_results.json` | 本轮执行结果 + `run_id`/`finished_at`/`junit_sha256` 绑定 | `conftest.py`（经 `run_automation.py`） | C3/C5/C8、回写 |
| `junit.xml` | pytest JUnit 原始报告 | pytest | C8 摘要校验 |
| `allure/` | Allure 原始结果（`allure generate` 的输入） | pytest | 报告 |
| `gate/release-gate.{json,md}` | C1–C8 判据结论 | `scripts/release_gate.py` | 投产放行 |
| `history/<时间戳>/` | **跑批前自动备份**的上一轮 `automation_results.json` + `junit.xml` | `scripts/run_automation.py` | 事后比对 |
| `zentao_bug_manifest.json` | 失败自动建单台账（用例→Bug ID） | `scripts/run_automation.py` | 缺陷闭环 |

## 跑批前备份（防"本轮没跑却拿旧结果过门禁"）

`run_automation.py` 每次启动先把上一轮的 `automation_results.json` / `junit.xml` 复制到
`history/<YYYYmmdd-HHMMSS>/`，再跑本轮。因此：

- 想证明"这轮真跑了"，比对 `gate/release-gate.json` 里 C8 的 `run_id` 与 `run_automation.py` 打印的 `RUN_ID`；
- 想证明"结果没被覆盖"，比对 `history/` 里最新一份与当前文件。

## 不落 artifacts 的东西

密钥、Token、真实手机号 → 只经环境变量传递，**不写入任何文件**。
