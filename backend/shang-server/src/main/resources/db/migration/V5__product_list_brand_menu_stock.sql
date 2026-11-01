-- ============================================================
-- V5: 商品列表配套修正
-- 1) 隐藏独立「品牌管理」菜单（类目页已含品牌，避免重复入口）
-- 2) 品牌按钮权限挂到「类目管理」下，角色配置仍可用
-- 3) 修正演示品牌命名；按商品品类对齐 brand_id
-- 4) 提高种子 SKU 库存，便于列表/库存页演示
-- ============================================================

-- 侧栏不再单独展示「品牌管理」（/brands 仍 redirect 到 /categories）
UPDATE identity.permission
SET visible = FALSE, updated_at = NOW()
WHERE id = 240 AND code = 'brands';

UPDATE identity.permission
SET name = '类目与品牌', updated_at = NOW()
WHERE id = 230 AND code = 'categories';

UPDATE identity.permission
SET parent_id = 230, updated_at = NOW()
WHERE id IN (241, 242, 243) AND code IN ('brand:add', 'brand:edit', 'brand:delete');

-- 演示品牌命名（类目页右侧品牌表）
UPDATE commerce.brand SET name = 'Apple', updated_at = NOW() WHERE id = 10;
UPDATE commerce.brand SET name = '联想', updated_at = NOW() WHERE id = 11;
UPDATE commerce.brand SET name = '家电精选', updated_at = NOW() WHERE id = 12;
UPDATE commerce.brand SET name = '数码配件', updated_at = NOW() WHERE id = 13;
UPDATE commerce.brand SET name = '生活家居', updated_at = NOW() WHERE id = 14;
UPDATE commerce.brand SET name = '美妆个护', updated_at = NOW() WHERE id = 15;
UPDATE commerce.brand SET name = '食品生鲜', updated_at = NOW() WHERE id = 16;
UPDATE commerce.brand SET name = '运动户外', updated_at = NOW() WHERE id = 17;

-- 手机类：按常见品牌粗分（演示用）
UPDATE commerce.spu SET brand_id = 10, updated_at = NOW() WHERE id IN (101, 102);
UPDATE commerce.spu SET brand_id = 10, updated_at = NOW() WHERE id IN (103, 104);
UPDATE commerce.spu SET brand_id = 10, updated_at = NOW() WHERE id IN (105, 106, 109, 111);

-- 抬高种子库存（可售量 = physical - locked）
UPDATE commerce.stock
SET physical_stock = GREATEST(physical_stock, 80),
    updated_at = NOW()
WHERE sku_id BETWEEN 10001 AND 10035
  AND deleted = FALSE;
