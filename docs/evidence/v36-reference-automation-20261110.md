# v3.6 全目录指代加购自动化（评测数据与覆盖率）

## 版本

- 尚品汇：P1 脚本与 CI，无后端/前端运行时变更
- 生产后端仍为 v3.4.3：`774f3a208b675dffe3aeea9f8de09c430f60b453406407a7365fa1a583e5d26b`
- 平台：用例 332 → 392（新增 `reference-cart-auto` 30 条 × 2 target）

## 新增脚本

| 脚本 | 作用 |
|---|---|
| `scripts/eval/catalog_snapshot.py` | 从管理端导出 ON SPU + SKU 快照 |
| `scripts/eval/generate_reference_cases.py` | 每个 ON SPU 生成 `REF-<spuId>-named-add` |
| `scripts/eval/check_reference_coverage.py` | 覆盖率门禁；缺失/重复/断言缺失退出 1 |
| `scripts/eval/mine_failure_candidates.py` | 只读挖掘 FAILED/HANDOFF/tool_error/retrieval_miss，脱敏候选 |
| `scripts/eval/reference_health.py` | 指代加购健康快照（失败率/加购成功率/检索命中率） |
| `scripts/eval/approve_candidates.py` | 人工审批候选；未审批不进入正式 suite |
| `scripts/eval/run_platform_suite.py` | 在平台运行指定 suite 并汇总 pass/fail |
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

## 平台并发修复

平台仓库提交：`2b4f893 fix(agent-test): isolate shangpin sessions per run`

`shangpin` 适配器原先 `fresh=true` 不带 `X-Chat-Client-Key`，同一测试手机并发时互相结束会话。现每个平台 run 使用独立 `platform-<test_run_id>`，2-worker 全量：

- graph `v35-refcart` 50/50
- legacy `v35-refcart` 50/50
- graph `v36-refcart-auto` 30/30
- legacy `v36-refcart-auto` 30/30

## 多规格快捷选项

多规格回答里的“【规格N】”渲染为快捷选项；点击发送 `规格N`，后端承接上一轮 `《商品》有多个规格` 的商品上下文，直接进入加购/确认。生产 Playwright 场景通过。

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

## 管理端：指代加购健康页

- 接口：`GET /admin/customer-service/reference-health?days=1|7|14|30`
- 指标：运行失败率、转人工率、加购成功率、待确认加购、指代反问率、规格反问、检索无命中率。
- 页面：管理端「智能客服 → 指代加购健康」，权限复用 `cs-stats`。
- 生产实测：1/7/14/30 天均返回 200。

## 索引级品牌别名（V31 + V32 配置表）

- Flyway V31 新增 `product_chunk.alias_text`；V32 新增 `customerservice.product_alias` 配置表。
- 别名从代码迁到配置表（alias → target_token），运营新增一行即可扩展，无需改代码。
- `ProductAliasService` 60 秒缓存；`ProductIndexService` 写入索引，`RetrievalService` 做查询扩展。
- 生产重建 30 条切片成功；“推荐华为手机”返回 `HUAWEI Mate X5` 首卡。

## 健康页趋势图

- 新增 `GET /admin/customer-service/reference-health/trend?days=14`，按天返回运行/失败/转人工/加购/反问/检索命中。
- 管理端「指代加购健康」新增每日趋势柱状图（运行 vs 指代反问），支持今日/7/14/30 天切换。

## 部署说明

P1 只改仓库脚本与 GitHub Actions；管理端健康页面需要部署前端。
本轮后端 jar SHA256：`774f3a208b675dffe3aeea9f8de09c430f60b453406407a7365fa1a583e5d26b`，管理端备份 `/var/www/shang-admin.old-20261111-1100`，生产服务 active。
