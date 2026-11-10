# v3.6 全目录指代加购自动化（评测数据与覆盖率）

## 版本

- 尚品汇：P1 脚本与 CI，无后端/前端运行时变更
- 生产后端仍为 v3.4.3：`5859ca38db1bee9c5209c14083be3fe594e2c53ae0558c5629ed7ea9fa2583b6`
- 平台：用例 332 → 392（新增 `reference-cart-auto` 30 条 × 2 target）

## 新增脚本

| 脚本 | 作用 |
|---|---|
| `scripts/eval/catalog_snapshot.py` | 从管理端导出 ON SPU + SKU 快照 |
| `scripts/eval/generate_reference_cases.py` | 每个 ON SPU 生成 `REF-<spuId>-named-add` |
| `scripts/eval/check_reference_coverage.py` | 覆盖率门禁；缺失/重复/断言缺失退出 1 |
| `scripts/eval/mine_failure_candidates.py` | 只读挖掘 FAILED/HANDOFF/tool_error/retrieval_miss，脱敏候选 |
| `scripts/eval/sync_platform_cases.py` | 按 `(targetId, sourceId)` upsert 平台 |

`reference-cart-auto` 与手写 `reference-cart-50` 分开：目录变化只更新 auto 套件，不重写手写期望值。

## 验证

| 项 | 结果 |
|---|---|
| 脚本单测 | 8/8 |
| 生产目录快照 | 30 个 ON SPU |
| 覆盖率门禁 | 30/30，缺失 0、重复 0、断言问题 0 |
| 平台 graph `v36-refcart-auto` | 30/30 |
| 平台 legacy `v36-refcart-auto` | 30/30 |
| trace 挖掘 | 本地 31 条候选，PII 已掩码 |
| 平台总数 | 392 |

## CI

`.github/workflows/eval.yml` 每周在 Golden 前执行：

1. 脚本单测；
2. 目录快照；
3. 生成 auto 用例；
4. 覆盖率门禁（硬失败）；
5. 上传覆盖率报告与 trace 候选；
6. 原有 200 条 Golden 评估。

## 使用

```bash
python scripts/eval/catalog_snapshot.py --base-url https://admin.bettery.cn \
  --admin-username admin --admin-password '***' --out /tmp/catalog.json
python scripts/eval/generate_reference_cases.py \
  --catalog /tmp/catalog.json --out /tmp/reference-cart-auto.jsonl
python scripts/eval/check_reference_coverage.py \
  --catalog /tmp/catalog.json --dataset /tmp/reference-cart-auto.jsonl \
  --report /tmp/reference-coverage.md

PLATFORM_BASE_URL=https://agent.bettery.cn \
PLATFORM_EMAIL='***' PLATFORM_PASSWORD='***' \
python scripts/eval/sync_platform_cases.py \
  --dataset /tmp/reference-cart-auto.jsonl \
  --target AT-CA66D70EFB --target AT-0B7DEB56CD
```

新增 SPU 后：快照 → 生成 → 覆盖率 → 同步平台，四步即可；不需要改手写 50 条。

## 部署说明

P1 只改仓库脚本与 GitHub Actions，不需要重启生产后端/前端；生产服务保持 v3.4.3。
