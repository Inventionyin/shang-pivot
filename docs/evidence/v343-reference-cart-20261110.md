# v3.4.3 全品类上文商品指代加购（生产证据）

## 版本

- 尚品汇：`02186a1` + 本次复查修复，tag `v3.4.3`
- 生产 jar SHA256：`6b05a3f0c4febdb310e2b658ec7dcbd2292b69f5ad8826948ed67c2fbe3176b7`
- 数据库：无新 Flyway 迁移（沿用 V30）
- 平台：332 条（新增 `reference-cart` 50 条 × 2 target）

## 修复

1. **通用指代解析器** `ProductReferenceResolver`
   - 商品卡上下文从“最近 1 条”扩到最近 3 条、最多 10 个 SPU。
   - 解析顺序：序号 → 品牌别名 → 品类（categoryName）→ 商品名精确/前缀/包含 → 原话 token → 检索兜底。
   - 多候选一律 `Ambiguous` 反问，绝不按列表顺序取第一个。
   - iPhone 15 不命中 iPhone 15 Pro；带数字完整型号优先精确命中。
   - 目录外“洗碗机”不被向量近邻的冰箱/洗衣机硬凑。
   - graph 与 legacy 共用同一解析器。
2. **商品卡“加入购物车”**
   - 卡片底部新增按钮；点击只传 `spuId`，后端按当前商品名走同一加购/确认链路。
   - 不新增写操作路径；复用 `pending_action`、金额/数量确认策略、幂等与审计。
   - 多规格商品仍先反问规格（如 iPhone 15 Pro、Xiaomi 14 Pro）。
3. **SKU 选择**
   - 所有 SKU 共享的规格值（如口红“丝绒雾面”）不再被当成用户已选规格。
   - 商品名精确包含某个 SKU 标题时（iPhone 15 128G、挂耳咖啡 20袋），直接命中该 SKU。
   - 多规格回答展示“规格1/规格2”快捷选项；点击后发送 `规格N`，后端承接上一轮 `《商品》有多个规格` 的上下文，不再重新问商品。
4. **数据集**
   - 新增 `reference-cart-50.jsonl`：30 条全 SPU 点名加购 + 10 条品类/品牌 + 10 条边界对抗。
   - 新增 `ProductReferenceResolverTest`：30 SPU × 6 种说法 = 180 条确定性矩阵。
   - 既有 Golden 200 条期望值未改。

## 本地验收

| 项 | 结果 |
|---|---|
| `GraphGoldenIntegrationTest` | 3/3（Golden 200 + Reference 50 + BadLlm），0 失败 |
| `ProductReferenceResolverTest` | 10/10（含 30×6 矩阵） |
| `IntentRouterTest` | 17/17 |
| `AgentGraphIntegrationTest` | 8/8 |
| `ChatSessionLifecycleIntegrationTest` | 8/8 |
| `shang-customerservice` 全量测试 | 全部通过 |
| 商城 `npm run build` | 通过 |
| 本地 Playwright `@ai` | 8/8（含商品卡按钮、返回恢复、滚动） |

## 平台验收

| 验证 | 结果 |
|---|---|
| graph core20 | 20/20，Judge 0.948 |
| legacy core20 | 20/20，Judge 0.972 |
| graph reference-cart-50 | 50/50（单 worker，避免测试会话互相结束） |
| legacy reference-cart-50 | 50/50（单 worker） |
| 测试用例总数 | 232 → 332 |

说明：并发 2 worker 时平台 `shangpin` 适配器会在同一测试账号上互相结束会话，导致少量 `会话已结束` 假失败；单 worker 全量 50/50，属于平台测试执行隔离问题，已在证据中记录。

## 生产验收

- 商品卡按钮 `spuId=142` → `cart-confirm:10021`（美的风冷冰箱，4599 元）。
- “推荐 vivo X100 → 把手机加进购物车” → `cart-confirm:10012`。
- “推荐 HUAWEI Mate X5 → 把华为加进购物车” → `cart-confirm:10009`。
- “对比 iPhone 15 / iPhone 15 Pro → 把 iPhone 15 加进购物车” → `sku:102`（基础款，未命中 Pro）。
- “把洗碗机加进购物车” → 无卡片，回答“当前会话没有可操作的商品”。
- **30 个在售 SPU 点名加购 canary：30/30 通过**（`scripts/eval/production-reference-canary.json`）。

## 部署

| 项 | 值 |
|---|---|
| 后端 | `systemctl restart shang-backend`，active，`/api/home` 200 |
| 备份 | `/opt/shang-pivot/backups/pre-v343-20261109-1215/`（jar/env/shang.dump） |
| 前端备份 | `/var/www/shang.old-20261110-2200` |
| 前端 | `/var/www/shang`（1.4M） |
| 迁移 | 无新迁移 |

## 回滚

```bash
cp /opt/shang-pivot/backups/pre-v343-20261109-1215/shang-server.jar /opt/shang-pivot/shang-server.jar
cp /opt/shang-pivot/backups/pre-v343-20261109-1215/.env /opt/shang-pivot/.env
rm -rf /var/www/shang
mv /var/www/shang.old-20261110-2200 /var/www/shang
systemctl restart shang-backend
```
