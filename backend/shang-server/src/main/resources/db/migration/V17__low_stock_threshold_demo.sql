-- ============================================================
-- V17: V1.0 种子「可演示化」补丁（数据-only，不改任何代码逻辑）
--
-- 背景（两个验收盲区）：
--   1) 库存预警：V4 种子与 V5「physical_stock >= 80」抬量后，所有 SKU 的可售
--      均 ≥ 80，而 low_threshold 恒为 10 → Dashboard.lowStockCount 恒为 0、
--      「库存管理 → 低库存 Tab」永远为空，FR-016 / AC-010 无法验收。
--   2) 商品状态分支：种子 30 个 SPU 全为 ON，没有草稿也没有下架 →
--      PRD §8.11「删除（仅草稿）」、上下架切换都无法演示。
--
-- 做法：
--   A. 给 5 个高价慢销 SKU 设置与其安全库存相符的阈值，形成两档预警。
--   B. 追加 2 个演示商品：1 个草稿（可演示删除草稿 / 上架）、1 个下架（可演示上架）。
--      两者均非 ON，因此不会出现在 C 端（C 端只展示上架商品），商品目录不受污染。
--
-- 预期结果：
--   在售商品（SPU）= 30    SKU 总数 = 47    低库存预警 = 3    待发货订单 = 2
--   低库存 Tab：低库存 3 行（Mate X5 / ThinkPad X1 / ROG 枪神）
--             关注   2 行（MacBook Air 13 M3 / vivo X100）
--   商品列表：32 行（30 上架 + 1 草稿 + 1 下架）
-- ============================================================

-- ---------- A. 库存预警两档口径 ----------
-- 低库存：可售 ≤ 阈值   （折叠屏 / 商务本 / 电竞本，单价高、周转慢）
UPDATE commerce.stock SET low_threshold = 100, updated_at = NOW()
WHERE warehouse_id = 1 AND deleted = FALSE AND sku_id IN (10009, 10015, 10016);

-- 关注：阈值 < 可售 ≤ 阈值 × 1.5   （轻薄本：阈值 60 < 可售 80 ≤ 90）
UPDATE commerce.stock SET low_threshold = 60, updated_at = NOW()
WHERE warehouse_id = 1 AND deleted = FALSE AND sku_id = 10013;

-- 关注：阈值 100 < 可售 140 ≤ 150   （影像旗舰）
UPDATE commerce.stock SET low_threshold = 100, updated_at = NOW()
WHERE warehouse_id = 1 AND deleted = FALSE AND sku_id = 10012;

-- ---------- B. 草稿 / 下架 演示商品 ----------
-- B1. 草稿：iPhone 15 Pro Max 256G（类目 手机=10，品牌 Apple=10）
INSERT INTO commerce.spu (id, category_id, brand_id, name, subtitle, pics, videos, detail_html, status)
SELECT 247, 10, 10, 'iPhone 15 Pro Max 256G', 'A17 Pro · 5 倍长焦',
       '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_7.jpg"]'::jsonb,
       '[]'::jsonb, '<p>A17 Pro · 5 倍长焦</p>', 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM commerce.spu WHERE id = 247);

INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT 10046, 247, 'iPhone 15 Pro Max 256G', 9999.00, 6999.30, 'PKU-10046',
       '{"容量":"256GB","颜色":"原色钛金属"}'::jsonb,
       '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_7.jpg', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku WHERE id = 10046);

INSERT INTO commerce.stock (id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
SELECT 10046, 10046, 1, 60, 0, 10
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock WHERE id = 10046);

-- B2. 下架：折叠露营桌 铝合金（类目 运动户外=17，品牌 运动户外=17）
INSERT INTO commerce.spu (id, category_id, brand_id, name, subtitle, pics, videos, detail_html, status)
SELECT 248, 17, 17, '折叠露营桌 铝合金', '轻量折叠，承重 30kg',
       '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_148.jpg"]'::jsonb,
       '[]'::jsonb, '<p>轻量折叠，承重 30kg</p>', 'OFF'
WHERE NOT EXISTS (SELECT 1 FROM commerce.spu WHERE id = 248);

INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT 10047, 248, '折叠露营桌 铝合金', 359.00, 251.30, 'PKU-10047',
       '{}'::jsonb,
       '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_148.jpg', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku WHERE id = 10047);

INSERT INTO commerce.stock (id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
SELECT 10047, 10047, 1, 40, 0, 10
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock WHERE id = 10047);