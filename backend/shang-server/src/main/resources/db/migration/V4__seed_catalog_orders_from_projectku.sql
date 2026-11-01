-- ============================================================
-- V4: 从 ProjectKu 迁移一批商品与订单演示数据（图片已上传 MinIO）
-- 对象键：PRODUCT/seed/product_{源id}.jpg
-- 库内 pic/pics 存与上传接口一致的代理 URL：
--   /admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_N.jpg
-- 前端 resolveMediaUrl 对该前缀原样返回，经鉴权代理从 OSS 读流
-- ID：类目/品牌 10-17，SPU 100+源id，SKU 10001+，订单 101+（避开 V1 id=1）
-- ============================================================

-- 文件元数据（与 MinIO 对象对应；view 接口不依赖此表，便于后台文件管理）
INSERT INTO identity.file_object (id, bucket, object_key, content_type, size_bytes, biz_type, uploader_id, visibility)
SELECT * FROM (VALUES
    (201::bigint, 'shang-pivot', 'PRODUCT/seed/product_1.jpg', 'image/webp', 1838::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (202::bigint, 'shang-pivot', 'PRODUCT/seed/product_2.jpg', 'image/webp', 53182::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (203::bigint, 'shang-pivot', 'PRODUCT/seed/product_3.jpg', 'image/webp', 5788::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (204::bigint, 'shang-pivot', 'PRODUCT/seed/product_4.jpg', 'image/webp', 4694::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (205::bigint, 'shang-pivot', 'PRODUCT/seed/product_5.jpg', 'image/webp', 6898::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (206::bigint, 'shang-pivot', 'PRODUCT/seed/product_6.jpg', 'image/webp', 9146::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (207::bigint, 'shang-pivot', 'PRODUCT/seed/product_9.jpg', 'image/webp', 5852::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (208::bigint, 'shang-pivot', 'PRODUCT/seed/product_11.jpg', 'image/webp', 6250::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (209::bigint, 'shang-pivot', 'PRODUCT/seed/product_21.jpg', 'image/webp', 11368::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (210::bigint, 'shang-pivot', 'PRODUCT/seed/product_22.jpg', 'image/webp', 11871::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (211::bigint, 'shang-pivot', 'PRODUCT/seed/product_23.jpg', 'image/webp', 6312::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (212::bigint, 'shang-pivot', 'PRODUCT/seed/product_24.jpg', 'image/webp', 14436::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (213::bigint, 'shang-pivot', 'PRODUCT/seed/product_25.jpg', 'image/webp', 18820::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (214::bigint, 'shang-pivot', 'PRODUCT/seed/product_27.jpg', 'image/webp', 15350::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (215::bigint, 'shang-pivot', 'PRODUCT/seed/product_41.jpg', 'image/webp', 10226::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (216::bigint, 'shang-pivot', 'PRODUCT/seed/product_42.jpg', 'image/webp', 3474::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (217::bigint, 'shang-pivot', 'PRODUCT/seed/product_43.jpg', 'image/webp', 3474::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (218::bigint, 'shang-pivot', 'PRODUCT/seed/product_44.jpg', 'image/webp', 3826::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (219::bigint, 'shang-pivot', 'PRODUCT/seed/product_61.jpg', 'image/webp', 5480::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (220::bigint, 'shang-pivot', 'PRODUCT/seed/product_62.jpg', 'image/webp', 3698::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (221::bigint, 'shang-pivot', 'PRODUCT/seed/product_64.jpg', 'image/webp', 9078::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (222::bigint, 'shang-pivot', 'PRODUCT/seed/product_68.jpg', 'image/webp', 6176::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (223::bigint, 'shang-pivot', 'PRODUCT/seed/product_81.jpg', 'image/webp', 6384::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (224::bigint, 'shang-pivot', 'PRODUCT/seed/product_84.jpg', 'image/webp', 8434::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (225::bigint, 'shang-pivot', 'PRODUCT/seed/product_101.jpg', 'image/webp', 7596::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (226::bigint, 'shang-pivot', 'PRODUCT/seed/product_106.jpg', 'image/webp', 11408::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (227::bigint, 'shang-pivot', 'PRODUCT/seed/product_121.jpg', 'image/webp', 31488::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (228::bigint, 'shang-pivot', 'PRODUCT/seed/product_126.jpg', 'image/webp', 15236::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (229::bigint, 'shang-pivot', 'PRODUCT/seed/product_141.jpg', 'image/webp', 26960::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC'),
    (230::bigint, 'shang-pivot', 'PRODUCT/seed/product_146.jpg', 'image/webp', 18034::bigint, 'PRODUCT', NULL::bigint, 'PUBLIC')
) AS v(id, bucket, object_key, content_type, size_bytes, biz_type, uploader_id, visibility)
WHERE NOT EXISTS (SELECT 1 FROM identity.file_object f WHERE f.object_key = v.object_key AND f.deleted = FALSE);


INSERT INTO commerce.category (id, parent_id, name, level, sort, status)
SELECT * FROM (VALUES
    (10::bigint, 0::bigint, '手机', 1, 10, 'ENABLED'),
    (11::bigint, 0::bigint, '电脑/办公', 1, 11, 'ENABLED'),
    (12::bigint, 0::bigint, '家电', 1, 12, 'ENABLED'),
    (13::bigint, 0::bigint, '数码配件', 1, 13, 'ENABLED'),
    (14::bigint, 0::bigint, '家居收纳', 1, 14, 'ENABLED'),
    (15::bigint, 0::bigint, '美妆个护', 1, 15, 'ENABLED'),
    (16::bigint, 0::bigint, '食品生鲜', 1, 16, 'ENABLED'),
    (17::bigint, 0::bigint, '运动户外', 1, 17, 'ENABLED')
) AS v(id, parent_id, name, level, sort, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.category c WHERE c.id = v.id);

INSERT INTO commerce.brand (id, name, logo, sort, status)
SELECT * FROM (VALUES
    (10::bigint, 'Apple', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg', 10, 'ENABLED'),
    (11::bigint, '联想/ThinkPad', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_22.jpg', 11, 'ENABLED'),
    (12::bigint, '家电精选', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_41.jpg', 12, 'ENABLED'),
    (13::bigint, '数码配件', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_61.jpg', 13, 'ENABLED'),
    (14::bigint, '家居生活', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_81.jpg', 14, 'ENABLED'),
    (15::bigint, '美妆个护', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_101.jpg', 15, 'ENABLED'),
    (16::bigint, '食品生鲜', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_121.jpg', 16, 'ENABLED'),
    (17::bigint, '运动户外', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_141.jpg', 17, 'ENABLED')
) AS v(id, name, logo, sort, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.brand b WHERE b.id = v.id);

INSERT INTO commerce.spu (id, category_id, brand_id, name, subtitle, pics, videos, detail_html, status)
SELECT * FROM (VALUES
    (101::bigint, 10::bigint, 10::bigint, 'iPhone 15 Pro 128G', 'A17 Pro 芯片，旗舰性能', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg"]'::jsonb, '[]'::jsonb, '<p>A17 Pro 芯片，旗舰性能</p>', 'ON'),
    (102::bigint, 10::bigint, 10::bigint, 'iPhone 15 128G', 'A16 芯片，性能均衡', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg"]'::jsonb, '[]'::jsonb, '<p>A16 芯片，性能均衡</p>', 'ON'),
    (103::bigint, 10::bigint, 10::bigint, 'Xiaomi 14 Pro 12+256G', '徕卡光学，旗舰影像', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_3.jpg"]'::jsonb, '[]'::jsonb, '<p>徕卡光学，旗舰影像</p>', 'ON'),
    (104::bigint, 10::bigint, 10::bigint, 'Redmi K70 12+256G', '性能强悍，价格亲民', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_4.jpg"]'::jsonb, '[]'::jsonb, '<p>性能强悍，价格亲民</p>', 'ON'),
    (105::bigint, 10::bigint, 10::bigint, 'HUAWEI Mate X5', '折叠旗舰，大屏体验', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_5.jpg"]'::jsonb, '[]'::jsonb, '<p>折叠旗舰，大屏体验</p>', 'ON'),
    (106::bigint, 10::bigint, 10::bigint, 'OPPO Find N3', '折叠轻薄，旗舰体验', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_6.jpg"]'::jsonb, '[]'::jsonb, '<p>折叠轻薄，旗舰体验</p>', 'ON'),
    (109::bigint, 10::bigint, 10::bigint, '荣耀 Magic6 Pro', '骁龙8 Gen3 旗舰', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_9.jpg"]'::jsonb, '[]'::jsonb, '<p>骁龙8 Gen3 旗舰</p>', 'ON'),
    (111::bigint, 10::bigint, 10::bigint, 'vivo X100', '自研影像旗舰', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_11.jpg"]'::jsonb, '[]'::jsonb, '<p>自研影像旗舰</p>', 'ON'),
    (121::bigint, 11::bigint, 11::bigint, 'MacBook Air 13 M3 16G', '轻薄便携，续航出众', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_21.jpg"]'::jsonb, '[]'::jsonb, '<p>轻薄便携，续航出众</p>', 'ON'),
    (122::bigint, 11::bigint, 11::bigint, 'ThinkPad X1 Carbon', '高可靠商务轻薄', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_22.jpg"]'::jsonb, '[]'::jsonb, '<p>高可靠商务轻薄</p>', 'ON'),
    (123::bigint, 11::bigint, 11::bigint, 'ROG 枪神 笔记本', '高刷电竞游戏本', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_23.jpg"]'::jsonb, '[]'::jsonb, '<p>高刷电竞游戏本</p>', 'ON'),
    (124::bigint, 11::bigint, 11::bigint, '联想 拯救者 Y7000', '高性价比游戏本', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_24.jpg"]'::jsonb, '[]'::jsonb, '<p>高性价比游戏本</p>', 'ON'),
    (125::bigint, 11::bigint, 11::bigint, 'Dell U2723QE 27寸 4K', 'Type-C 一线通', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_25.jpg"]'::jsonb, '[]'::jsonb, '<p>Type-C 一线通</p>', 'ON'),
    (127::bigint, 11::bigint, 11::bigint, '罗技 MX Master 3s', '人体工学鼠标', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_27.jpg"]'::jsonb, '[]'::jsonb, '<p>人体工学鼠标</p>', 'ON'),
    (141::bigint, 12::bigint, 12::bigint, '海尔 纤诺 洗衣机', '大容量变频', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_41.jpg"]'::jsonb, '[]'::jsonb, '<p>大容量变频</p>', 'ON'),
    (142::bigint, 12::bigint, 12::bigint, '美的 风冷 冰箱', '多门风冷', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_42.jpg"]'::jsonb, '[]'::jsonb, '<p>多门风冷</p>', 'ON'),
    (143::bigint, 12::bigint, 12::bigint, '戴森 V12 吸尘器', '强劲吸力', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_43.jpg"]'::jsonb, '[]'::jsonb, '<p>强劲吸力</p>', 'ON'),
    (144::bigint, 12::bigint, 12::bigint, '石头 扫拖机器人', '激光导航', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_44.jpg"]'::jsonb, '[]'::jsonb, '<p>激光导航</p>', 'ON'),
    (161::bigint, 13::bigint, 13::bigint, '索尼 WH-1000XM5', '旗舰降噪耳机', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_61.jpg"]'::jsonb, '[]'::jsonb, '<p>旗舰降噪耳机</p>', 'ON'),
    (162::bigint, 13::bigint, 13::bigint, 'AirPods Pro 2', '主动降噪', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_62.jpg"]'::jsonb, '[]'::jsonb, '<p>主动降噪</p>', 'ON'),
    (164::bigint, 13::bigint, 13::bigint, 'Anker 65W 充电器', '氮化镓快充', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_64.jpg"]'::jsonb, '[]'::jsonb, '<p>氮化镓快充</p>', 'ON'),
    (168::bigint, 13::bigint, 13::bigint, 'Apple Watch SE', '智能手表', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_68.jpg"]'::jsonb, '[]'::jsonb, '<p>智能手表</p>', 'ON'),
    (181::bigint, 14::bigint, 14::bigint, '抽屉式收纳盒', '桌面收纳', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_81.jpg"]'::jsonb, '[]'::jsonb, '<p>桌面收纳</p>', 'ON'),
    (184::bigint, 14::bigint, 14::bigint, '落地台灯', '氛围灯', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_84.jpg"]'::jsonb, '[]'::jsonb, '<p>氛围灯</p>', 'ON'),
    (201::bigint, 15::bigint, 15::bigint, '温和保湿洁面乳', '氨基酸配方，清洁同时不紧绷', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_101.jpg"]'::jsonb, '[]'::jsonb, '<p>氨基酸配方，清洁同时不紧绷</p>', 'ON'),
    (206::bigint, 15::bigint, 15::bigint, '丝绒雾面口红', '显白不拔干，多色可选', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_106.jpg"]'::jsonb, '[]'::jsonb, '<p>显白不拔干，多色可选</p>', 'ON'),
    (221::bigint, 16::bigint, 16::bigint, '每日坚果 750g', '混合坚果，独立小包装', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_121.jpg"]'::jsonb, '[]'::jsonb, '<p>混合坚果，独立小包装</p>', 'ON'),
    (226::bigint, 16::bigint, 16::bigint, '挂耳咖啡 20袋', '阿拉比卡咖啡豆，中度烘焙', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_126.jpg"]'::jsonb, '[]'::jsonb, '<p>阿拉比卡咖啡豆，中度烘焙</p>', 'ON'),
    (241::bigint, 17::bigint, 17::bigint, '减震跑步鞋 男款', '适合日常慢跑训练', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_141.jpg"]'::jsonb, '[]'::jsonb, '<p>适合日常慢跑训练</p>', 'ON'),
    (246::bigint, 17::bigint, 17::bigint, '双人帐篷 防雨款', '三季适用，搭建方便', '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_146.jpg"]'::jsonb, '[]'::jsonb, '<p>三季适用，搭建方便</p>', 'ON')
) AS v(id, category_id, brand_id, name, subtitle, pics, videos, detail_html, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.spu s WHERE s.id = v.id);

INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (10001::bigint, 101::bigint, 'iPhone 15 Pro 128G', 7999.00::numeric, 5599.30::numeric, 'PKU-10001', '{"容量":"128GB","颜色":"原色钛金属"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg', 'ENABLED'),
    (10002::bigint, 101::bigint, 'iPhone 15 Pro 128G', 8999.00::numeric, 6299.30::numeric, 'PKU-10002', '{"容量":"256GB","颜色":"原色钛金属"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg', 'ENABLED'),
    (10003::bigint, 101::bigint, 'iPhone 15 Pro 128G', 10999.00::numeric, 7699.30::numeric, 'PKU-10003', '{"容量":"512GB","颜色":"白色钛金属"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg', 'ENABLED'),
    (10004::bigint, 102::bigint, 'iPhone 15 128G', 5999.00::numeric, 4199.30::numeric, 'PKU-10004', '{"容量":"128GB","颜色":"蓝色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg', 'ENABLED'),
    (10005::bigint, 102::bigint, 'iPhone 15 128G', 6999.00::numeric, 4899.30::numeric, 'PKU-10005', '{"容量":"256GB","颜色":"粉色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg', 'ENABLED'),
    (10006::bigint, 103::bigint, 'Xiaomi 14 Pro 12+256G', 4999.00::numeric, 3499.30::numeric, 'PKU-10006', '{"内存":"12GB","存储":"256GB","颜色":"黑色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_3.jpg', 'ENABLED'),
    (10007::bigint, 103::bigint, 'Xiaomi 14 Pro 12+256G', 5599.00::numeric, 3919.30::numeric, 'PKU-10007', '{"内存":"16GB","存储":"512GB","颜色":"黑色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_3.jpg', 'ENABLED'),
    (10008::bigint, 104::bigint, 'Redmi K70 12+256G', 2299.00::numeric, 1609.30::numeric, 'PKU-10008', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_4.jpg', 'ENABLED'),
    (10009::bigint, 105::bigint, 'HUAWEI Mate X5', 12999.00::numeric, 9099.30::numeric, 'PKU-10009', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_5.jpg', 'ENABLED'),
    (10010::bigint, 106::bigint, 'OPPO Find N3', 9999.00::numeric, 6999.30::numeric, 'PKU-10010', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_6.jpg', 'ENABLED'),
    (10011::bigint, 109::bigint, '荣耀 Magic6 Pro', 6499.00::numeric, 4549.30::numeric, 'PKU-10011', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_9.jpg', 'ENABLED'),
    (10012::bigint, 111::bigint, 'vivo X100', 5299.00::numeric, 3709.30::numeric, 'PKU-10012', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_11.jpg', 'ENABLED'),
    (10013::bigint, 121::bigint, 'MacBook Air 13 M3 16G', 9999.00::numeric, 6999.30::numeric, 'PKU-10013', '{"内存":"16GB","颜色":"午夜色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_21.jpg', 'ENABLED'),
    (10014::bigint, 121::bigint, 'MacBook Air 13 M3 16G', 11999.00::numeric, 8399.30::numeric, 'PKU-10014', '{"内存":"24GB","颜色":"星光色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_21.jpg', 'ENABLED'),
    (10015::bigint, 122::bigint, 'ThinkPad X1 Carbon', 12999.00::numeric, 9099.30::numeric, 'PKU-10015', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_22.jpg', 'ENABLED'),
    (10016::bigint, 123::bigint, 'ROG 枪神 笔记本', 14999.00::numeric, 10499.30::numeric, 'PKU-10016', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_23.jpg', 'ENABLED'),
    (10017::bigint, 124::bigint, '联想 拯救者 Y7000', 6999.00::numeric, 4899.30::numeric, 'PKU-10017', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_24.jpg', 'ENABLED'),
    (10018::bigint, 125::bigint, 'Dell U2723QE 27寸 4K', 3999.00::numeric, 2799.30::numeric, 'PKU-10018', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_25.jpg', 'ENABLED'),
    (10019::bigint, 127::bigint, '罗技 MX Master 3s', 599.00::numeric, 419.30::numeric, 'PKU-10019', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_27.jpg', 'ENABLED'),
    (10020::bigint, 141::bigint, '海尔 纤诺 洗衣机', 3299.00::numeric, 2309.30::numeric, 'PKU-10020', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_41.jpg', 'ENABLED'),
    (10021::bigint, 142::bigint, '美的 风冷 冰箱', 4599.00::numeric, 3219.30::numeric, 'PKU-10021', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_42.jpg', 'ENABLED'),
    (10022::bigint, 143::bigint, '戴森 V12 吸尘器', 3999.00::numeric, 2799.30::numeric, 'PKU-10022', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_43.jpg', 'ENABLED'),
    (10023::bigint, 144::bigint, '石头 扫拖机器人', 2599.00::numeric, 1819.30::numeric, 'PKU-10023', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_44.jpg', 'ENABLED'),
    (10024::bigint, 161::bigint, '索尼 WH-1000XM5', 2599.00::numeric, 1819.30::numeric, 'PKU-10024', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_61.jpg', 'ENABLED'),
    (10025::bigint, 162::bigint, 'AirPods Pro 2', 1999.00::numeric, 1399.30::numeric, 'PKU-10025', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_62.jpg', 'ENABLED'),
    (10026::bigint, 164::bigint, 'Anker 65W 充电器', 199.00::numeric, 139.30::numeric, 'PKU-10026', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_64.jpg', 'ENABLED'),
    (10027::bigint, 168::bigint, 'Apple Watch SE', 2199.00::numeric, 1539.30::numeric, 'PKU-10027', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_68.jpg', 'ENABLED'),
    (10028::bigint, 181::bigint, '抽屉式收纳盒', 69.00::numeric, 48.30::numeric, 'PKU-10028', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_81.jpg', 'ENABLED'),
    (10029::bigint, 184::bigint, '落地台灯', 199.00::numeric, 139.30::numeric, 'PKU-10029', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_84.jpg', 'ENABLED'),
    (10030::bigint, 201::bigint, '温和保湿洁面乳', 79.00::numeric, 55.30::numeric, 'PKU-10030', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_101.jpg', 'ENABLED'),
    (10031::bigint, 206::bigint, '丝绒雾面口红', 139.00::numeric, 97.30::numeric, 'PKU-10031', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_106.jpg', 'ENABLED'),
    (10032::bigint, 221::bigint, '每日坚果 750g', 89.00::numeric, 62.30::numeric, 'PKU-10032', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_121.jpg', 'ENABLED'),
    (10033::bigint, 226::bigint, '挂耳咖啡 20袋', 69.00::numeric, 48.30::numeric, 'PKU-10033', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_126.jpg', 'ENABLED'),
    (10034::bigint, 241::bigint, '减震跑步鞋 男款', 399.00::numeric, 279.30::numeric, 'PKU-10034', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_141.jpg', 'ENABLED'),
    (10035::bigint, 246::bigint, '双人帐篷 防雨款', 599.00::numeric, 419.30::numeric, 'PKU-10035', '{}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_146.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

INSERT INTO commerce.stock (id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
SELECT * FROM (VALUES
    (10001::bigint, 10001::bigint, 1::bigint, 100, 0, 10),
    (10002::bigint, 10002::bigint, 1::bigint, 80, 0, 10),
    (10003::bigint, 10003::bigint, 1::bigint, 50, 0, 10),
    (10004::bigint, 10004::bigint, 1::bigint, 120, 0, 10),
    (10005::bigint, 10005::bigint, 1::bigint, 70, 0, 10),
    (10006::bigint, 10006::bigint, 1::bigint, 200, 0, 10),
    (10007::bigint, 10007::bigint, 1::bigint, 80, 0, 10),
    (10008::bigint, 10008::bigint, 1::bigint, 300, 0, 10),
    (10009::bigint, 10009::bigint, 1::bigint, 50, 0, 10),
    (10010::bigint, 10010::bigint, 1::bigint, 60, 0, 10),
    (10011::bigint, 10011::bigint, 1::bigint, 120, 0, 10),
    (10012::bigint, 10012::bigint, 1::bigint, 140, 0, 10),
    (10013::bigint, 10013::bigint, 1::bigint, 50, 0, 10),
    (10014::bigint, 10014::bigint, 1::bigint, 20, 0, 10),
    (10015::bigint, 10015::bigint, 1::bigint, 30, 0, 10),
    (10016::bigint, 10016::bigint, 1::bigint, 20, 0, 10),
    (10017::bigint, 10017::bigint, 1::bigint, 80, 0, 10),
    (10018::bigint, 10018::bigint, 1::bigint, 25, 0, 10),
    (10019::bigint, 10019::bigint, 1::bigint, 200, 0, 10),
    (10020::bigint, 10020::bigint, 1::bigint, 40, 0, 10),
    (10021::bigint, 10021::bigint, 1::bigint, 35, 0, 10),
    (10022::bigint, 10022::bigint, 1::bigint, 30, 0, 10),
    (10023::bigint, 10023::bigint, 1::bigint, 50, 0, 10),
    (10024::bigint, 10024::bigint, 1::bigint, 60, 0, 10),
    (10025::bigint, 10025::bigint, 1::bigint, 90, 0, 10),
    (10026::bigint, 10026::bigint, 1::bigint, 300, 0, 10),
    (10027::bigint, 10027::bigint, 1::bigint, 80, 0, 10),
    (10028::bigint, 10028::bigint, 1::bigint, 500, 0, 10),
    (10029::bigint, 10029::bigint, 1::bigint, 200, 0, 10),
    (10030::bigint, 10030::bigint, 1::bigint, 300, 0, 10),
    (10031::bigint, 10031::bigint, 1::bigint, 400, 0, 10),
    (10032::bigint, 10032::bigint, 1::bigint, 400, 0, 10),
    (10033::bigint, 10033::bigint, 1::bigint, 300, 0, 10),
    (10034::bigint, 10034::bigint, 1::bigint, 260, 0, 10),
    (10035::bigint, 10035::bigint, 1::bigint, 180, 0, 10)
) AS v(id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock s WHERE s.id = v.id);

INSERT INTO commerce."order" (id, order_no, customer_id, customer_name, status, total_amount, pay_amount, freight, address_snapshot, paid_at, ship_at, received_at)
SELECT * FROM (VALUES
    (101::bigint, 'SO202609140101', 9001::bigint, '张三', 'PENDING_SHIP', 7999.00::numeric, 7999.00::numeric, 0::numeric,
     '{"name":"张三","phone":"13800000000","region":"北京市朝阳区","detail":"建国路 88 号"}'::jsonb,
     NOW() - INTERVAL '2 hours', NULL, NULL),
    (102::bigint, 'SO202609140102', 9002::bigint, 'Alice', 'PENDING_PAY', 4999.00::numeric, 4999.00::numeric, 0::numeric,
     '{"name":"Alice","phone":"13900000002","region":"上海市浦东新区","detail":"世纪大道 1 号"}'::jsonb,
     NULL, NULL, NULL),
    (103::bigint, 'SO202609140103', 9003::bigint, '李四', 'PENDING_RECEIVE', 10598.00::numeric, 10598.00::numeric, 0::numeric,
     '{"name":"李四","phone":"13700000003","region":"广州市天河区","detail":"体育西路 100 号"}'::jsonb,
     NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day', NULL),
    (104::bigint, 'SO202609140104', 9001::bigint, '张三', 'COMPLETED', 1999.00::numeric, 1999.00::numeric, 0::numeric,
     '{"name":"张三","phone":"13800000000","region":"北京市朝阳区","detail":"建国路 88 号"}'::jsonb,
     NOW() - INTERVAL '10 days', NOW() - INTERVAL '8 days', NOW() - INTERVAL '5 days'),
    (105::bigint, 'SO202609140105', 9002::bigint, 'Alice', 'CLOSED', 2299.00::numeric, 2299.00::numeric, 0::numeric,
     '{"name":"Alice","phone":"13900000002","region":"上海市浦东新区","detail":"世纪大道 1 号"}'::jsonb,
     NULL, NULL, NULL),
    (106::bigint, 'SO202609140106', 9003::bigint, '李四', 'PENDING_SHIP', 4009.00::numeric, 4009.00::numeric, 10::numeric,
     '{"name":"李四","phone":"13700000003","region":"广州市天河区","detail":"体育西路 100 号"}'::jsonb,
     NOW() - INTERVAL '5 hours', NULL, NULL)
) AS v(id, order_no, customer_id, customer_name, status, total_amount, pay_amount, freight, address_snapshot, paid_at, ship_at, received_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce."order" o WHERE o.id = v.id);

INSERT INTO commerce.order_item (id, order_id, sku_id, spu_id, title, pic, price, quantity, specs, subtotal)
SELECT * FROM (VALUES
    (101::bigint, 101::bigint, 10001::bigint, 101::bigint, 'iPhone 15 Pro 128G', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_1.jpg', 7999.00::numeric, 1, '{"容量":"128GB","颜色":"原色钛金属"}'::jsonb, 7999.00::numeric),
    (102::bigint, 102::bigint, 10006::bigint, 103::bigint, 'Xiaomi 14 Pro 12+256G', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_3.jpg', 4999.00::numeric, 1, '{"内存":"12GB","存储":"256GB","颜色":"黑色"}'::jsonb, 4999.00::numeric),
    (103::bigint, 103::bigint, 10013::bigint, 121::bigint, 'MacBook Air 13 M3 16G', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_21.jpg', 9999.00::numeric, 1, '{"内存":"16GB","颜色":"午夜色"}'::jsonb, 9999.00::numeric),
    (104::bigint, 103::bigint, 10019::bigint, 127::bigint, '罗技 MX Master 3s', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_27.jpg', 599.00::numeric, 1, '{}'::jsonb, 599.00::numeric),
    (105::bigint, 104::bigint, 10025::bigint, 162::bigint, 'AirPods Pro 2', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_62.jpg', 1999.00::numeric, 1, '{}'::jsonb, 1999.00::numeric),
    (106::bigint, 105::bigint, 10008::bigint, 104::bigint, 'Redmi K70 12+256G', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_4.jpg', 2299.00::numeric, 1, '{}'::jsonb, 2299.00::numeric),
    (107::bigint, 106::bigint, 10022::bigint, 143::bigint, '戴森 V12 吸尘器', '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_43.jpg', 3999.00::numeric, 1, '{}'::jsonb, 3999.00::numeric)
) AS v(id, order_id, sku_id, spu_id, title, pic, price, quantity, specs, subtotal)
WHERE NOT EXISTS (SELECT 1 FROM commerce.order_item i WHERE i.id = v.id);

INSERT INTO commerce.payment (id, order_id, channel, amount, status, trade_no, paid_at)
SELECT * FROM (VALUES
    (101::bigint, 101::bigint, 'SANDBOX', 7999.00::numeric, 'PAID', 'SANDBOX-T-101', NOW() - INTERVAL '2 hours'),
    (102::bigint, 103::bigint, 'SANDBOX', 10598.00::numeric, 'PAID', 'SANDBOX-T-103', NOW() - INTERVAL '3 days'),
    (103::bigint, 104::bigint, 'SANDBOX', 1999.00::numeric, 'PAID', 'SANDBOX-T-104', NOW() - INTERVAL '10 days'),
    (104::bigint, 106::bigint, 'SANDBOX', 4009.00::numeric, 'PAID', 'SANDBOX-T-106', NOW() - INTERVAL '5 hours')
) AS v(id, order_id, channel, amount, status, trade_no, paid_at)
WHERE NOT EXISTS (SELECT 1 FROM commerce.payment p WHERE p.id = v.id);

INSERT INTO commerce.shipment (id, order_id, carrier, tracking_no, status, traces)
SELECT * FROM (VALUES
    (101::bigint, 103::bigint, '顺丰速运', 'SF109140103', 'SHIPPED',
     '[{"time":"已揽收","desc":"商家已发货"},{"time":"运输中","desc":"离开广州转运中心"}]'::jsonb),
    (102::bigint, 104::bigint, '京东物流', 'JD109140104', 'DELIVERED',
     '[{"time":"已签收","desc":"客户已签收"}]'::jsonb)
) AS v(id, order_id, carrier, tracking_no, status, traces)
WHERE NOT EXISTS (SELECT 1 FROM commerce.shipment s WHERE s.id = v.id);

UPDATE commerce."order" SET close_reason = '超时未支付（演示）'
WHERE id = 105 AND (close_reason IS NULL OR close_reason = '');

INSERT INTO commerce.after_sale (id, after_sale_no, order_id, customer_id, type, reason, evidence_pics, status, refund_amount)
SELECT 101, 'AS202609140101', 104, 9001, 'REFUND', '七天无理由（演示）',
       '["/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_62.jpg"]'::jsonb, 'PENDING_AUDIT', 1999.00
WHERE NOT EXISTS (SELECT 1 FROM commerce.after_sale a WHERE a.id = 101);
