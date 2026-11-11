# 评测数据与目录驱动用例

## 两套数据集

| 文件 | 用途 |
|---|---|
| `golden-200.jsonl` | 原有 200 条 Golden（业务规则回归） |
| `session-continuity-6.jsonl` | 同一会话多轮上下文连续性 |
| `reference-cart-50.jsonl` | 手写全品类指代加购 50 条（30 SPU + 10 品类/品牌 + 10 边界） |
| `reference-cart-auto.jsonl` | 由在售目录自动生成，每个 ON SPU 一条点名加购 |
| `conversation-repair-40.jsonl` | 多轮澄清/话轮承接 40 条（口语确认、裸序号、规格序号、目录外） |

`reference-cart-auto` 与手写 50 条分开：目录新增/下架时只更新 auto 套件，不重写手写期望值。
`conversation-repair` 用于锁住“确认 就买这个 / 1 / 第二个 / 规格1”这类多轮断点；反馈差评与 trace 候选经人工复核后补入。

## 自动化脚本

| 脚本 | 作用 |
|---|---|
| `catalog_snapshot.py` | 从管理端导出 ON SPU + SKU 快照 |
| `generate_reference_cases.py` | 每个 ON SPU 生成 `REF-<spuId>-named-add` |
| `check_reference_coverage.py` | 覆盖率门禁（缺失/重复/断言缺失退出 1） |
| `mine_failure_candidates.py` | 只读挖掘失败/转人工/工具失败/检索无命中，脱敏候选 |
| `reference_health.py` | 指代加购健康快照（失败率/加购成功率/检索命中率） |
| `approve_candidates.py` | 人工审批候选；未审批不进入正式 suite |
| `sync_platform_cases.py` | 按 `(targetId, sourceId)` upsert 平台 |
| `run_platform_suite.py` | 在平台运行指定 suite 并汇总 pass/fail |

## 每周 CI

`.github/workflows/eval.yml` 在 Golden 评估前执行：

```bash
python -m unittest discover -s scripts/eval/tests -v

python scripts/eval/catalog_snapshot.py \
  --base-url http://127.0.0.1:8086 --out /tmp/catalog.json
python scripts/eval/generate_reference_cases.py \
  --catalog /tmp/catalog.json --out /tmp/reference-cart-auto.jsonl
python scripts/eval/check_reference_coverage.py \
  --catalog /tmp/catalog.json --dataset /tmp/reference-cart-auto.jsonl \
  --report /tmp/reference-coverage.md
```

覆盖率门禁：任一在售 SPU 缺 `REF-<spuId>-named-add`、sourceId 重复或 case 缺可失败断言，命令退出 1。

多轮澄清 40 条由 `ConversationRepairExportTest` 导出：

```bash
cd backend
./mvnw -q -pl shang-customerservice -am \
  -Dtest=ConversationRepairExportTest -Dsurefire.failIfNoSpecifiedTests=false test
# 产物：shang-customerservice/target/conversation-repair-40.jsonl
```

## 生产 trace 挖候选

```bash
python scripts/eval/mine_failure_candidates.py \
  --host 127.0.0.1 --port 5432 --database shang --user shang --password '***' \
  --since-days 14 --out /tmp/reference-candidates.jsonl
```

除失败/转人工/工具失败/检索无命中外，现已纳入：

- `message_feedback.rating=DOWN` 的差评 run（`negative_feedback=...`）；
- 上一轮助手问过“请回复序号/您指的是哪一款”但本轮未承接（`clarify_without_followup`）；
- 上一轮出现“没有可操作的商品”（`no_context_claim`）。

只读；输出只作候选，必须人工脱敏复核后才能进入正式 suite。

## 同步到 AI 测试平台

凭据只从环境变量读取：

```bash
PLATFORM_BASE_URL=https://agent.bettery.cn \
PLATFORM_EMAIL='***' PLATFORM_PASSWORD='***' \
python scripts/eval/sync_platform_cases.py \
  --dataset /tmp/reference-cart-auto.jsonl \
  --target AT-CA66D70EFB --target AT-0B7DEB56CD
```

按 `(targetId, sourceId)` upsert；目录新增 SPU 后重跑生成 + 同步即可。
