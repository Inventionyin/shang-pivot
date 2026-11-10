# 评测数据与目录驱动用例

## 两套数据集

| 文件 | 用途 |
|---|---|
| `golden-200.jsonl` | 原有 200 条 Golden（业务规则回归） |
| `session-continuity-6.jsonl` | 同一会话多轮上下文连续性 |
| `reference-cart-50.jsonl` | 手写全品类指代加购 50 条（30 SPU + 10 品类/品牌 + 10 边界） |
| `reference-cart-auto.jsonl` | 由在售目录自动生成，每个 ON SPU 一条点名加购 |

`reference-cart-auto` 与手写 50 条分开：目录新增/下架时只更新 auto 套件，不重写手写期望值。

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

## 生产 trace 挖候选

```bash
python scripts/eval/mine_failure_candidates.py \
  --host 127.0.0.1 --port 5432 --database shang --user shang --password '***' \
  --since-days 14 --out /tmp/reference-candidates.jsonl
```

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
