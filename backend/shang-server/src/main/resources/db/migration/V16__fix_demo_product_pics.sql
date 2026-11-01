-- ============================================================
-- V16: 修正 V1 演示商品「示例智能手机 X1」图片
-- 原 pics 为 demo/product/x1-*.jpg（占位 objectKey，桶内不存在）→ 改为 V4 已上传的 PRODUCT/seed 图
-- ============================================================

UPDATE commerce.spu
SET pics = '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg","/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg"]'::jsonb,
    updated_at = NOW()
WHERE id = 1
  AND (
    pics::text LIKE '%demo/product/%'
    OR pics::text LIKE '%x1-1.jpg%'
  );

UPDATE commerce.sku
SET pic = '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg'
WHERE id = 1 AND (pic IS NULL OR pic LIKE 'demo/%');

UPDATE commerce.sku
SET pic = '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg'
WHERE id = 2 AND (pic IS NULL OR pic LIKE 'demo/%');
