-- ============================================================
-- V18: 首页运营位去营销化（V2.0 版本纪律修正，数据-only）
--
-- 背景：V10/V14 的运营位种子带 V4.0（营销/直播）语义：
--   slot 2 = 百亿补贴、slot 3 = 限时秒杀、slot 4 = 直播入口（外链直播间），
--   而 V2.0 的运营位槽位口径只有：首页 Banner / 活动位 / 搜索热词 / 热门精选。
--   版本时间线上 V2 不得出现 V4 内容（正如 V1 不得出现 V2），故修正为中性「活动位」。
--
-- 做法：
--   1. 三个营销槽位改名为「活动位 · XX」，语义落回 V2 的「活动位」槽位类型。
--   2. 移除直播间外链条目，改为站内 SPU 选品（品牌专区）。
--   3. Banner 里的示例外链（example.com）改为站内类目跳转，避免 Demo 出现死链。
--   槽位 code 保持不变（C 端按 code 取槽位，改名只影响展示标题）。
--
-- 预期结果：首页运营位 = 首页 Banner / 活动位 · 热门精选 / 活动位 · 新品上架 /
--           活动位 · 品牌专区 / 搜索热词 / 热门精选 SPU，无营销与直播语义。
-- ============================================================

-- ---------- 1. 槽位标题去营销化 ----------
UPDATE commerce.home_slot SET title = '活动位 · 热门精选', updated_at = NOW() WHERE code = 'home_promo_subsidy';
UPDATE commerce.home_slot SET title = '活动位 · 新品上架', updated_at = NOW() WHERE code = 'home_promo_flash';
UPDATE commerce.home_slot SET title = '活动位 · 品牌专区', updated_at = NOW() WHERE code = 'home_promo_live';

-- ---------- 2. 移除直播间外链条目，改为站内 SPU 选品 ----------
DELETE FROM commerce.home_slot_item
WHERE link_type = 'URL' AND link_value LIKE '%live.example.com%';

INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8031::bigint, 4::bigint, 1, 101::bigint, NULL, 'SPU', '101', 'iPhone 15 Pro 128G',   'ON'),
    (8032::bigint, 4::bigint, 2, 121::bigint, NULL, 'SPU', '121', 'MacBook Air 13 M3 16G', 'ON'),
    (8033::bigint, 4::bigint, 3, 143::bigint, NULL, 'SPU', '143', '戴森 V12 吸尘器',       'ON'),
    (8034::bigint, 4::bigint, 4, 161::bigint, NULL, 'SPU', '161', '索尼 WH-1000XM5',       'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- ---------- 3. Banner 死链改站内类目 ----------
UPDATE commerce.home_slot_item
SET link_type = 'CATEGORY', link_value = '11', title = '电脑/办公专场'
WHERE id = 8003 AND link_value LIKE '%example.com%';