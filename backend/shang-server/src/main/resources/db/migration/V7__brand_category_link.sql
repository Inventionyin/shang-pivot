-- ============================================================
-- V7: 品牌归属类目；类目点击可直接筛品牌
-- ============================================================

ALTER TABLE commerce.brand
    ADD COLUMN IF NOT EXISTS category_id BIGINT;

COMMENT ON COLUMN commerce.brand.category_id IS '所属类目 ID（可空；后台按类目筛选品牌）';

-- 演示数据：品牌与顶级类目对齐
UPDATE commerce.brand SET category_id = 1,  updated_at = NOW() WHERE id = 1;
UPDATE commerce.brand SET category_id = 10, updated_at = NOW() WHERE id = 10;
UPDATE commerce.brand SET category_id = 11, updated_at = NOW() WHERE id = 11;
UPDATE commerce.brand SET category_id = 12, updated_at = NOW() WHERE id = 12;
UPDATE commerce.brand SET category_id = 13, updated_at = NOW() WHERE id = 13;
UPDATE commerce.brand SET category_id = 14, updated_at = NOW() WHERE id = 14;
UPDATE commerce.brand SET category_id = 15, updated_at = NOW() WHERE id = 15;
UPDATE commerce.brand SET category_id = 16, updated_at = NOW() WHERE id = 16;
UPDATE commerce.brand SET category_id = 17, updated_at = NOW() WHERE id = 17;

-- 数码电器(1) 下补一条属性，便于点父类目也能看到
INSERT INTO commerce.attribute (id, category_id, name, type, options, sort)
SELECT 2, 1, '品牌档次', 'SPEC', '["入门","旗舰"]'::jsonb, 2
WHERE NOT EXISTS (SELECT 1 FROM commerce.attribute a WHERE a.id = 2);
