-- ============================================================
-- V14: V2 新增能力演示数据（会员 / 地址·车·收藏足迹 / 运营位内容 /
--      评价 / 出入库 / 订单端类型 / 销量与定时上架 / 站点品牌微调）
-- 依赖：V4 商品订单 · V8–V11 表结构 · V10 预置槽位
-- 会员 ID 与 V4 订单 customer_id（9001–9003）对齐
-- ============================================================

-- ---------- 1. 会员 ----------
INSERT INTO identity.customer (id, phone, nickname, avatar, gender, status, last_login_at, created_at)
SELECT * FROM (VALUES
    (9001::bigint, '13800000001', '张三',   'PRODUCT/seed/product_1.jpg',  1::smallint, 'NORMAL', NOW() - INTERVAL '1 hour',  NOW() - INTERVAL '40 days'),
    (9002::bigint, '13900000002', 'Alice',  'PRODUCT/seed/product_3.jpg',  2::smallint, 'NORMAL', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '35 days'),
    (9003::bigint, '13700000003', '李四',   'PRODUCT/seed/product_21.jpg', 1::smallint, 'NORMAL', NOW() - INTERVAL '1 day',   NOW() - INTERVAL '30 days'),
    (9004::bigint, '13600000004', '王五',   NULL,                         0::smallint, 'NORMAL', NOW() - INTERVAL '2 days',  NOW() - INTERVAL '20 days'),
    (9005::bigint, '13500000005', '赵六',   NULL,                         2::smallint, 'FROZEN', NOW() - INTERVAL '15 days', NOW() - INTERVAL '25 days'),
    (9006::bigint, '13400000006', '陈七',   'PRODUCT/seed/product_62.jpg', 1::smallint, 'NORMAL', NOW() - INTERVAL '6 hours', NOW() - INTERVAL '12 days'),
    (9007::bigint, '13300000007', '周八',   NULL,                         0::smallint, 'NORMAL', NULL,                        NOW() - INTERVAL '5 days')
) AS v(id, phone, nickname, avatar, gender, status, last_login_at, created_at)
WHERE NOT EXISTS (SELECT 1 FROM identity.customer c WHERE c.id = v.id);

-- ---------- 2. 收货地址 ----------
INSERT INTO commerce.address (id, customer_id, name, phone, province, city, district, detail, is_default)
SELECT * FROM (VALUES
    (9101::bigint, 9001::bigint, '张三',  '13800000001', '北京市', '北京市', '朝阳区', '建国路 88 号', true),
    (9102::bigint, 9001::bigint, '张三',  '13800000001', '北京市', '北京市', '海淀区', '中关村大街 1 号', false),
    (9103::bigint, 9002::bigint, 'Alice', '13900000002', '上海市', '上海市', '浦东新区', '世纪大道 1 号', true),
    (9104::bigint, 9003::bigint, '李四',  '13700000003', '广东省', '广州市', '天河区', '体育西路 100 号', true),
    (9105::bigint, 9004::bigint, '王五',  '13600000004', '浙江省', '杭州市', '西湖区', '文三路 259 号', true),
    (9106::bigint, 9006::bigint, '陈七',  '13400000006', '四川省', '成都市', '武侯区', '人民南路 4 段', true)
) AS v(id, customer_id, name, phone, province, city, district, detail, is_default)
WHERE NOT EXISTS (SELECT 1 FROM commerce.address a WHERE a.id = v.id);

-- ---------- 3. 购物车 ----------
INSERT INTO commerce.cart (id, customer_id)
SELECT * FROM (VALUES
    (9201::bigint, 9001::bigint),
    (9202::bigint, 9002::bigint),
    (9204::bigint, 9004::bigint)
) AS v(id, customer_id)
WHERE NOT EXISTS (SELECT 1 FROM commerce.cart c WHERE c.id = v.id OR c.customer_id = v.customer_id);

INSERT INTO commerce.cart_item (id, cart_id, sku_id, quantity, selected)
SELECT v.id, c.id, v.sku_id, v.quantity, v.selected
FROM (VALUES
    (9211::bigint, 9001::bigint, 10001::bigint, 1, true),
    (9212::bigint, 9001::bigint, 10025::bigint, 2, true),
    (9213::bigint, 9002::bigint, 10006::bigint, 1, true),
    (9214::bigint, 9004::bigint, 10013::bigint, 1, false),
    (9215::bigint, 9004::bigint, 10019::bigint, 1, true)
) AS v(id, customer_id, sku_id, quantity, selected)
JOIN commerce.cart c ON c.customer_id = v.customer_id
WHERE NOT EXISTS (SELECT 1 FROM commerce.cart_item i WHERE i.id = v.id);

-- ---------- 4. 收藏 / 足迹 ----------
INSERT INTO commerce.favorite (id, customer_id, spu_id, created_at)
SELECT * FROM (VALUES
    (9301::bigint, 9001::bigint, 101::bigint, NOW() - INTERVAL '3 days'),
    (9302::bigint, 9001::bigint, 162::bigint, NOW() - INTERVAL '2 days'),
    (9303::bigint, 9002::bigint, 103::bigint, NOW() - INTERVAL '1 day'),
    (9304::bigint, 9003::bigint, 121::bigint, NOW() - INTERVAL '5 hours'),
    (9305::bigint, 9006::bigint, 143::bigint, NOW() - INTERVAL '1 hour')
) AS v(id, customer_id, spu_id, created_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce.favorite f WHERE f.id = v.id);

INSERT INTO commerce.browse_history (id, customer_id, spu_id, last_at)
SELECT * FROM (VALUES
    (9401::bigint, 9001::bigint, 101::bigint, NOW() - INTERVAL '30 minutes'),
    (9402::bigint, 9001::bigint, 104::bigint, NOW() - INTERVAL '2 hours'),
    (9403::bigint, 9001::bigint, 162::bigint, NOW() - INTERVAL '1 day'),
    (9404::bigint, 9002::bigint, 103::bigint, NOW() - INTERVAL '4 hours'),
    (9405::bigint, 9003::bigint, 121::bigint, NOW() - INTERVAL '6 hours'),
    (9406::bigint, 9004::bigint, 127::bigint, NOW() - INTERVAL '12 hours'),
    (9407::bigint, 9006::bigint, 143::bigint, NOW() - INTERVAL '20 minutes')
) AS v(id, customer_id, spu_id, last_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce.browse_history b WHERE b.id = v.id);

-- ---------- 5. 订单补端类型 / 脱敏手机（便于端筛演示） ----------
UPDATE commerce."order" SET
    client_type = CASE id
        WHEN 101 THEN 'web'
        WHEN 102 THEN 'h5'
        WHEN 103 THEN 'mp'
        WHEN 104 THEN 'web'
        WHEN 105 THEN 'h5'
        WHEN 106 THEN 'mp'
        ELSE client_type
    END,
    customer_phone_mask = CASE id
        WHEN 101 THEN '138****0001'
        WHEN 102 THEN '139****0002'
        WHEN 103 THEN '137****0003'
        WHEN 104 THEN '138****0001'
        WHEN 105 THEN '139****0002'
        WHEN 106 THEN '137****0003'
        ELSE customer_phone_mask
    END
WHERE id BETWEEN 101 AND 106
  AND (client_type IS NULL OR customer_phone_mask IS NULL);

-- ---------- 6. SPU 销量 + 一条定时上架演示 ----------
UPDATE commerce.spu SET sold_count = CASE id
    WHEN 101 THEN 1280
    WHEN 103 THEN 860
    WHEN 104 THEN 2100
    WHEN 121 THEN 420
    WHEN 127 THEN 980
    WHEN 143 THEN 350
    WHEN 162 THEN 1560
    ELSE sold_count
END
WHERE id IN (101, 103, 104, 121, 127, 143, 162)
  AND sold_count = 0;

-- 选一个 OFF 商品做定时上架（若无 OFF 则跳过）；用 id 最大的 OFF，否则不动
UPDATE commerce.spu
SET schedule_on_at = NOW() + INTERVAL '2 days',
    status = 'OFF'
WHERE id = (
    SELECT id FROM commerce.spu
    WHERE deleted = FALSE AND status = 'OFF'
    ORDER BY id
    LIMIT 1
)
AND schedule_on_at IS NULL;

-- 若库中没有 OFF 商品，则把一条演示 SPU 设为定时上架（仍保持 ON，仅填时间窗供后台看字段；Job 扫到会处理）
-- 更直观：把 Redmi 相关挑一条设 schedule（不改 status，避免误下架）
UPDATE commerce.spu
SET schedule_on_at = NOW() + INTERVAL '1 day'
WHERE id = 104 AND schedule_on_at IS NULL;

-- ---------- 7. 站点品牌 ----------
UPDATE commerce.site_brand
SET name = '尚品汇',
    slogan = '选好物，享生活',
    logo_key = 'PRODUCT/seed/product_1.jpg',
    updated_at = NOW()
WHERE id = 1;

-- ---------- 8. 首页运营位内容 ----------
-- Banner
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8001::bigint, 1::bigint, 1, NULL::bigint, 'PRODUCT/seed/product_1.jpg',  'SPU',      '101',                    '秋季焕新 · iPhone',  'ON'),
    (8002::bigint, 1::bigint, 2, NULL::bigint, 'PRODUCT/seed/product_21.jpg', 'CATEGORY', '11',                     '笔记本专场',         'ON'),
    (8003::bigint, 1::bigint, 3, NULL::bigint, 'PRODUCT/seed/product_43.jpg', 'URL',      'https://www.example.com','品牌日活动',         'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- 百亿补贴（选品）
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8011::bigint, 2::bigint, 1, 101::bigint, NULL, 'SPU', '101', 'iPhone 15 Pro', 'ON'),
    (8012::bigint, 2::bigint, 2, 103::bigint, NULL, 'SPU', '103', 'Xiaomi 14 Pro', 'ON'),
    (8013::bigint, 2::bigint, 3, 104::bigint, NULL, 'SPU', '104', 'Redmi K70',     'ON'),
    (8014::bigint, 2::bigint, 4, 162::bigint, NULL, 'SPU', '162', 'AirPods Pro 2', 'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- 限时秒杀
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8021::bigint, 3::bigint, 1, 143::bigint, NULL, 'SPU', '143', '戴森 V12',        'ON'),
    (8022::bigint, 3::bigint, 2, 127::bigint, NULL, 'SPU', '127', '罗技 MX Master', 'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- 直播入口
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8031::bigint, 4::bigint, 1, NULL::bigint, 'PRODUCT/seed/product_62.jpg', 'URL', 'https://live.example.com/room/1001', '今晚 20:00 直播间', 'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- 热搜词
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8041::bigint, 5::bigint, 1, NULL::bigint, NULL, 'NONE', NULL, 'iPhone 15',  'ON'),
    (8042::bigint, 5::bigint, 2, NULL::bigint, NULL, 'NONE', NULL, '戴森吸尘器', 'ON'),
    (8043::bigint, 5::bigint, 3, NULL::bigint, NULL, 'NONE', NULL, 'MacBook',    'ON'),
    (8044::bigint, 5::bigint, 4, NULL::bigint, NULL, 'NONE', NULL, 'AirPods',    'ON'),
    (8045::bigint, 5::bigint, 5, NULL::bigint, NULL, 'NONE', NULL, '罗技鼠标',   'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- 热门精选 SPU
INSERT INTO commerce.home_slot_item (id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
SELECT * FROM (VALUES
    (8051::bigint, 6::bigint, 1, 101::bigint, NULL, 'SPU', '101', NULL, 'ON'),
    (8052::bigint, 6::bigint, 2, 121::bigint, NULL, 'SPU', '121', NULL, 'ON'),
    (8053::bigint, 6::bigint, 3, 162::bigint, NULL, 'SPU', '162', NULL, 'ON'),
    (8054::bigint, 6::bigint, 4, 143::bigint, NULL, 'SPU', '143', NULL, 'ON'),
    (8055::bigint, 6::bigint, 5, 127::bigint, NULL, 'SPU', '127', NULL, 'ON'),
    (8056::bigint, 6::bigint, 6, 103::bigint, NULL, 'SPU', '103', NULL, 'ON')
) AS v(id, slot_id, sort, spu_id, image_key, link_type, link_value, title, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot_item i WHERE i.id = v.id);

-- Banner 槽位时间窗（演示）
UPDATE commerce.home_slot
SET valid_from = NOW() - INTERVAL '7 days',
    valid_to   = NOW() + INTERVAL '60 days',
    updated_at = NOW()
WHERE code = 'home_banner' AND valid_from IS NULL;

UPDATE commerce.home_slot
SET valid_from = NOW() - INTERVAL '1 day',
    valid_to   = NOW() + INTERVAL '3 days',
    updated_at = NOW()
WHERE code = 'home_promo_flash' AND valid_from IS NULL;

-- ---------- 9. 评价（对齐已完成订单项 105=AirPods） ----------
INSERT INTO commerce.review (id, order_item_id, customer_id, spu_id, rating, content, pics, status, created_at)
SELECT * FROM (VALUES
    (8501::bigint, 105::bigint, 9001::bigint, 162::bigint, 5::smallint,
     '音质很棒，降噪出色，续航也够用，强烈推荐！',
     '["PRODUCT/seed/product_62.jpg"]'::jsonb, 'VISIBLE', NOW() - INTERVAL '4 days'),
    (8502::bigint, 103::bigint, 9003::bigint, 121::bigint, 4::smallint,
     '轻薄好用，续航满意；键盘手感一般。',
     NULL::jsonb, 'VISIBLE', NOW() - INTERVAL '20 hours'),
    (8503::bigint, 104::bigint, 9003::bigint, 127::bigint, 5::smallint,
     '打工人神器，跨设备切换很顺。',
     NULL::jsonb, 'VISIBLE', NOW() - INTERVAL '18 hours'),
    (8504::bigint, 101::bigint, 9001::bigint, 101::bigint, 3::smallint,
     '外观不错，但发货有点慢（演示隐藏）。',
     NULL::jsonb, 'HIDDEN', NOW() - INTERVAL '1 day')
) AS v(id, order_item_id, customer_id, spu_id, rating, content, pics, status, created_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce.review r WHERE r.id = v.id OR r.order_item_id = v.order_item_id);

-- ---------- 10. 出入库单 ----------
INSERT INTO commerce.in_out_order (id, bill_no, type, warehouse_id, operator_id, remark, related_order_no, created_at)
SELECT * FROM (VALUES
    (8601::bigint, 'PI202609200001', 'PURCHASE_IN', 1::bigint, 1::bigint, '秋季补货入库', NULL,              NOW() - INTERVAL '6 days'),
    (8602::bigint, 'ST202609200001', 'STOCKTAKE',   1::bigint, 1::bigint, '月度盘点微调', NULL,              NOW() - INTERVAL '3 days'),
    (8603::bigint, 'SO202609200001', 'SALE_OUT',    1::bigint, 1::bigint, '订单发货出库', 'SO202609140103', NOW() - INTERVAL '1 day'),
    (8604::bigint, 'PI202609200002', 'PURCHASE_IN', 1::bigint, 1::bigint, '耳机配件补货', NULL,              NOW() - INTERVAL '8 hours')
) AS v(id, bill_no, type, warehouse_id, operator_id, remark, related_order_no, created_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce.in_out_order o WHERE o.id = v.id OR o.bill_no = v.bill_no);

INSERT INTO commerce.in_out_item (id, bill_id, sku_id, qty, remark)
SELECT * FROM (VALUES
    (8611::bigint, 8601::bigint, 10001::bigint, 50,  'iPhone 补货'),
    (8612::bigint, 8601::bigint, 10006::bigint, 30,  '小米补货'),
    (8613::bigint, 8601::bigint, 10025::bigint, 80,  'AirPods 补货'),
    (8614::bigint, 8602::bigint, 10019::bigint, -2,  '盘亏'),
    (8615::bigint, 8602::bigint, 10022::bigint, 3,   '盘盈'),
    (8616::bigint, 8603::bigint, 10013::bigint, -1,  'MacBook 出库'),
    (8617::bigint, 8603::bigint, 10019::bigint, -1,  '鼠标出库'),
    (8618::bigint, 8604::bigint, 10025::bigint, 40,  '耳机再补')
) AS v(id, bill_id, sku_id, qty, remark)
WHERE NOT EXISTS (SELECT 1 FROM commerce.in_out_item i WHERE i.id = v.id);
