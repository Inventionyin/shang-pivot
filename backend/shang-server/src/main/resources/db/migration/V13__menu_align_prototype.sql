-- V13: 侧栏严格对齐原型 .stitch/原型/admin/sidebar.js
-- 顺序：概览 → 商品中心 → 订单中心 → 会员与内容 → 系统管理
-- 「会员与内容」含：会员管理 / 站点品牌 / 首页运营位 / 评价管理

-- 顶层目录排序（对齐原型）
UPDATE identity.permission SET sort = 2, updated_at = NOW() WHERE id = 200 AND code = 'product';
UPDATE identity.permission SET sort = 3, updated_at = NOW() WHERE id = 400 AND code = 'order';
UPDATE identity.permission
SET name = '会员与内容', icon = 'User', sort = 4, updated_at = NOW()
WHERE id = 600 AND code = 'member';
UPDATE identity.permission SET sort = 5, updated_at = NOW() WHERE id = 500 AND code = 'system';

-- 站点品牌、运营位挂到「会员与内容」
UPDATE identity.permission
SET parent_id = 600, sort = 2, updated_at = NOW()
WHERE id = 710 AND code = 'site-brand';

UPDATE identity.permission
SET parent_id = 600, sort = 3, updated_at = NOW()
WHERE id = 720 AND code = 'home-slots';

-- 评价管理从订单中心挪到「会员与内容」
UPDATE identity.permission
SET parent_id = 600, sort = 4, updated_at = NOW()
WHERE id = 430 AND code = 'reviews';

UPDATE identity.permission
SET sort = 1, updated_at = NOW()
WHERE id = 610 AND code = 'customers';

-- 空壳「内容运营」目录隐藏（子菜单已迁走）
UPDATE identity.permission
SET visible = false, sort = 99, updated_at = NOW()
WHERE id = 700 AND code = 'content';

-- 运营位标题对齐原型文案
UPDATE commerce.home_slot SET title = '搜索热词' WHERE code = 'home_hot_keywords';
UPDATE commerce.home_slot SET title = '热门精选 SPU' WHERE code = 'home_hot_spus';
