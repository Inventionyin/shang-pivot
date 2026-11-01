-- ============================================================
-- V19: 清除 V1__init 遗留的演示目录实体（原型 ↔ 实现一致性修正）
--
-- 背景：V1__init 播下了最早的占位数据——类目「数码电器」(1) /「手机通讯」(2) /
--       「家居生活」(3)、品牌「示例品牌」(1)、SPU「示例智能手机 X1」(1) 及其 2 个 SKU。
--       V4 又播下了真实商品目录（类目 10～17、品牌 10～17、SPU 101～246）。
--       两套并存的结果：类目树里多出「数码电器/手机通讯/家居生活」三条、品牌表多出
--       「示例品牌」一行（logo 指向不存在的 demo/brand/logo.png，显示为裂图）、
--       商品列表多出一个「示例智能手机 X1」。
--       而原型 `.stitch/原型/admin/categories.html` 与 products.html 里都没有这些 →
--       原型与实现不一致。本迁移删掉遗留数据，使实现与原型、种子口径一致。
--
-- 影响面：这些 id 未被订单/评价/购物车/收藏/足迹引用（已核对），删除无悬挂引用。
--
-- 预期结果（与 PRD-V1.0 §2.4 一致）：
--   一级类目 8 个 · 品牌 8 个 · SPU 32 个（在售 30 + 草稿 1 + 下架 1）· SKU 47 个
-- ============================================================

-- ---------- 1. 遗留 SPU 1「示例智能手机 X1」及其 SKU / 库存 / 流水 ----------
DELETE FROM commerce.stock_log
WHERE sku_id IN (SELECT id FROM commerce.sku WHERE spu_id = 1);

DELETE FROM commerce.stock
WHERE sku_id IN (SELECT id FROM commerce.sku WHERE spu_id = 1);

DELETE FROM commerce.sku WHERE spu_id = 1;

DELETE FROM commerce.spu WHERE id = 1;

-- ---------- 2. 遗留类目「数码电器」「手机通讯」「家居生活」及挂在其下的属性 ----------
DELETE FROM commerce.attribute WHERE category_id IN (1, 2, 3);

DELETE FROM commerce.category WHERE id IN (1, 2, 3);

-- ---------- 3. 遗留品牌「示例品牌」（logo 为不存在的 demo/ 路径） ----------
DELETE FROM commerce.brand WHERE id = 1;