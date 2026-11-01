-- ============================================================
-- V6: 多规格 SKU、锁定库存演示、类目属性种子
-- ============================================================

-- 帐篷：颜色规格（原仅 1 个 SKU）
INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (10036::bigint, 246::bigint, '双人帐篷 防雨款 军绿', 599.00::numeric, 419.30::numeric, 'PKU-10036',
     '{"材质":"防雨涤纶","颜色":"军绿"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_146.jpg', 'ENABLED'),
    (10037::bigint, 246::bigint, '双人帐篷 防雨款 卡其', 619.00::numeric, 433.30::numeric, 'PKU-10037',
     '{"材质":"防雨涤纶","颜色":"卡其"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_146.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

-- 跑步鞋：尺码/颜色
INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (10038::bigint, 241::bigint, '减震跑步鞋 男款 42码 黑', 399.00::numeric, 279.30::numeric, 'PKU-10038',
     '{"容量":"42码","颜色":"黑色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_141.jpg', 'ENABLED'),
    (10039::bigint, 241::bigint, '减震跑步鞋 男款 43码 白', 399.00::numeric, 279.30::numeric, 'PKU-10039',
     '{"容量":"43码","颜色":"白色"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_141.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

-- 挂耳咖啡 / 坚果：规格包装
INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (10040::bigint, 226::bigint, '挂耳咖啡 20袋 中烘', 69.00::numeric, 48.30::numeric, 'PKU-10040',
     '{"规格":"20袋","颜色":"中烘"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_126.jpg', 'ENABLED'),
    (10041::bigint, 226::bigint, '挂耳咖啡 40袋 深烘', 119.00::numeric, 83.30::numeric, 'PKU-10041',
     '{"规格":"40袋","颜色":"深烘"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_126.jpg', 'ENABLED'),
    (10042::bigint, 221::bigint, '每日坚果 750g', 89.00::numeric, 62.30::numeric, 'PKU-10042',
     '{"规格":"750g","颜色":"原味"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_121.jpg', 'ENABLED'),
    (10043::bigint, 221::bigint, '每日坚果 1.5kg', 159.00::numeric, 111.30::numeric, 'PKU-10043',
     '{"规格":"1.5kg","颜色":"原味"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_121.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

-- 口红：色号
INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (10044::bigint, 206::bigint, '丝绒雾面口红 豆沙', 139.00::numeric, 97.30::numeric, 'PKU-10044',
     '{"材质":"丝绒雾面","颜色":"豆沙"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_106.jpg', 'ENABLED'),
    (10045::bigint, 206::bigint, '丝绒雾面口红 正红', 139.00::numeric, 97.30::numeric, 'PKU-10045',
     '{"材质":"丝绒雾面","颜色":"正红"}'::jsonb, '/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_106.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

-- 更新原单规格 SKU 的 specs，便于编辑页读出规格维
UPDATE commerce.sku
SET specs = '{"材质":"防雨涤纶","颜色":"墨绿"}'::jsonb, updated_at = NOW()
WHERE id = 10035;

UPDATE commerce.sku
SET specs = '{"容量":"41码","颜色":"黑灰"}'::jsonb, updated_at = NOW()
WHERE id = 10034;

UPDATE commerce.sku
SET specs = '{"规格":"20袋","颜色":"中烘"}'::jsonb, updated_at = NOW()
WHERE id = 10033;

UPDATE commerce.sku
SET specs = '{"规格":"750g","颜色":"原味"}'::jsonb, updated_at = NOW()
WHERE id = 10032;

UPDATE commerce.sku
SET specs = '{"材质":"丝绒雾面","颜色":"番茄红"}'::jsonb, updated_at = NOW()
WHERE id = 10031;

-- 新 SKU 库存
INSERT INTO commerce.stock (id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
SELECT * FROM (VALUES
    (10036::bigint, 10036::bigint, 1::bigint, 120, 0, 10),
    (10037::bigint, 10037::bigint, 1::bigint, 90, 0, 10),
    (10038::bigint, 10038::bigint, 1::bigint, 150, 0, 10),
    (10039::bigint, 10039::bigint, 1::bigint, 140, 0, 10),
    (10040::bigint, 10040::bigint, 1::bigint, 200, 0, 10),
    (10041::bigint, 10041::bigint, 1::bigint, 160, 0, 10),
    (10042::bigint, 10042::bigint, 1::bigint, 220, 0, 10),
    (10043::bigint, 10043::bigint, 1::bigint, 180, 0, 10),
    (10044::bigint, 10044::bigint, 1::bigint, 300, 0, 10),
    (10045::bigint, 10045::bigint, 1::bigint, 280, 0, 10)
) AS v(id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock s WHERE s.id = v.id);

-- 演示「锁定占用」：待发货订单占用部分库存（iPhone / 戴森）
UPDATE commerce.stock
SET locked_stock = 2, updated_at = NOW()
WHERE sku_id = 10001 AND physical_stock >= 2;

UPDATE commerce.stock
SET locked_stock = 1, updated_at = NOW()
WHERE sku_id = 10022 AND physical_stock >= 1;

-- 类目属性（点击类目后右侧「属性」可见）
INSERT INTO commerce.attribute (id, category_id, name, type, options, sort)
SELECT * FROM (VALUES
    (10::bigint, 10::bigint, '颜色', 'SPEC', '["黑色","白色","蓝色","原色钛"]'::jsonb, 1),
    (11::bigint, 10::bigint, '容量', 'SPEC', '["128GB","256GB","512GB"]'::jsonb, 2),
    (12::bigint, 17::bigint, '颜色', 'SPEC', '["军绿","卡其","黑灰"]'::jsonb, 1),
    (13::bigint, 17::bigint, '尺码', 'SPEC', '["41码","42码","43码"]'::jsonb, 2),
    (14::bigint, 16::bigint, '规格', 'SPEC', '["750g","1.5kg","20袋","40袋"]'::jsonb, 1),
    (15::bigint, 15::bigint, '色号', 'SPEC', '["豆沙","正红","番茄红"]'::jsonb, 1)
) AS v(id, category_id, name, type, options, sort)
WHERE NOT EXISTS (SELECT 1 FROM commerce.attribute a WHERE a.id = v.id);
