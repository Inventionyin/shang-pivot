-- ============================================================
-- V15: 纠正站点 Logo —— V14 误把商品图写成 logo_key
-- C 端未配置时使用静态资源 /logo.png（设计稿 docs/02-design/brand/logo.png）
-- ============================================================

UPDATE commerce.site_brand
SET logo_key = NULL,
    updated_at = NOW()
WHERE id = 1
  AND (
    logo_key IS NULL
    OR logo_key LIKE 'PRODUCT/%'
    OR logo_key LIKE '%/seed/product_%'
  );
