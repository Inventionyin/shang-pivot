-- ============================================================
-- V11: 出入库单据 · TRADE 配置 · 承运商字典 · V2 菜单/权限种子
-- 依据 docs/04-architecture/v2.0/数据模型.md §4–§7、接口设计.md §8–§9
-- ============================================================

-- ---------- in_out_order / in_out_item ----------
CREATE TABLE IF NOT EXISTS commerce.in_out_order (
    id                BIGINT PRIMARY KEY,
    bill_no           VARCHAR(64)  NOT NULL,
    type              VARCHAR(32)  NOT NULL,
    warehouse_id      BIGINT       NOT NULL,
    operator_id       BIGINT,
    remark            VARCHAR(512),
    related_order_no  VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    trace_id          VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_in_out_bill_no ON commerce.in_out_order (bill_no);
CREATE INDEX IF NOT EXISTS idx_in_out_type_created ON commerce.in_out_order (type, created_at DESC);

CREATE TABLE IF NOT EXISTS commerce.in_out_item (
    id              BIGINT PRIMARY KEY,
    bill_id         BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    qty             INT          NOT NULL,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_in_out_item_bill ON commerce.in_out_item (bill_id);

COMMENT ON TABLE commerce.in_out_order IS '出入库单据：PURCHASE_IN / STOCKTAKE / SALE_OUT';
COMMENT ON COLUMN commerce.in_out_order.type IS 'PURCHASE_IN 采购入 / STOCKTAKE 盘点 / SALE_OUT 销售出（发货自动）';
COMMENT ON COLUMN commerce.in_out_item.qty IS '有符号数量：入正出负';

-- ---------- TRADE 系统配置 ----------
INSERT INTO identity.system_config (id, config_key, config_value, category, is_secret, remark)
SELECT * FROM (VALUES
    (10::bigint, 'order.pay-timeout-minutes', '15',   'TRADE', false, '待付款超时关单（分钟）'),
    (11::bigint, 'freight.free-threshold',    '99',   'TRADE', false, '免运门槛（元）'),
    (12::bigint, 'freight.default-amount',    '8',    'TRADE', false, '未满门槛默认运费（元）'),
    (13::bigint, 'home.feed-sort',            'sold', 'TRADE', false, '猜你喜欢补位排序：sold/new')
) AS v(id, config_key, config_value, category, is_secret, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM identity.system_config c WHERE c.id = v.id OR c.config_key = v.config_key
);

-- ---------- 物流承运商字典 ----------
INSERT INTO identity.dict (id, code, name, remark)
SELECT 2, 'carrier', '物流承运商', '发货可选承运商'
WHERE NOT EXISTS (SELECT 1 FROM identity.dict WHERE id = 2 OR code = 'carrier');

INSERT INTO identity.dict_item (id, dict_id, label, value, sort)
SELECT * FROM (VALUES
    (21::bigint, 2::bigint, '顺丰速运', 'SF', 1),
    (22::bigint, 2::bigint, '中通快递', 'ZTO', 2),
    (23::bigint, 2::bigint, '圆通速递', 'YTO', 3),
    (24::bigint, 2::bigint, '韵达快递', 'YD', 4),
    (25::bigint, 2::bigint, '模拟快递', 'MOCK', 5)
) AS v(id, dict_id, label, value, sort)
WHERE NOT EXISTS (SELECT 1 FROM identity.dict_item i WHERE i.id = v.id);

-- ============================================================
-- V2 菜单与权限种子
-- ID 规划：600 会员中心 · 700 内容运营 · 订单/库存下挂按钮与菜单
-- 权限码对齐接口设计 §8–§9
-- ============================================================

-- 调整顶层排序：会员(2) 商品(3) 订单(4) 内容(5) 系统(6)；库存仍挂商品中心
UPDATE identity.permission SET sort = 3, updated_at = NOW() WHERE id = 200 AND code = 'product';
UPDATE identity.permission SET sort = 4, updated_at = NOW() WHERE id = 400 AND code = 'order';
UPDATE identity.permission SET sort = 6, updated_at = NOW() WHERE id = 500 AND code = 'system';

INSERT INTO identity.permission (id, parent_id, code, name, type, path, component, icon, sort, visible, status)
SELECT * FROM (VALUES
    -- ===== 会员中心 =====
    (600::bigint, 0::bigint,   'member',           '会员中心',     'DIR',    NULL,            NULL,                 'User',        2, true,  'ENABLED'),
    (610::bigint, 600::bigint, 'customers',        '会员管理',     'MENU',   '/customers',    'member/customer',    'Avatar',      1, true,  'ENABLED'),
    (611::bigint, 610::bigint, 'customer:list',    '会员查询',     'BUTTON', NULL,            NULL,                 NULL,          1, true,  'ENABLED'),
    (612::bigint, 610::bigint, 'customer:freeze',  '冻结/解冻',    'BUTTON', NULL,            NULL,                 NULL,          2, true,  'ENABLED'),

    -- ===== 内容运营 =====
    (700::bigint, 0::bigint,   'content',          '内容运营',     'DIR',    NULL,            NULL,                 'Picture',     5, true,  'ENABLED'),
    (710::bigint, 700::bigint, 'site-brand',       '站点品牌',     'MENU',   '/site-brand',   'content/site-brand', 'Trophy',      1, true,  'ENABLED'),
    (711::bigint, 710::bigint, 'content:brand',    '编辑站点品牌', 'BUTTON', NULL,            NULL,                 NULL,          1, true,  'ENABLED'),
    (720::bigint, 700::bigint, 'home-slots',       '首页运营位',   'MENU',   '/home-slots',   'content/home-slots', 'Grid',        2, true,  'ENABLED'),
    (721::bigint, 720::bigint, 'content:home',     '编辑运营位',   'BUTTON', NULL,            NULL,                 NULL,          1, true,  'ENABLED'),

    -- ===== 订单中心：评价管理 + 订单导出 =====
    (430::bigint, 400::bigint, 'reviews',          '评价管理',     'MENU',   '/reviews',      'order/review',       'ChatDotRound', 3, true,  'ENABLED'),
    (431::bigint, 430::bigint, 'review:list',      '评价查询',     'BUTTON', NULL,            NULL,                 NULL,          1, true,  'ENABLED'),
    (432::bigint, 430::bigint, 'review:hide',      '隐藏/恢复',    'BUTTON', NULL,            NULL,                 NULL,          2, true,  'ENABLED'),
    (412::bigint, 410::bigint, 'order:list',       '订单查询',     'BUTTON', NULL,            NULL,                 NULL,          2, true,  'ENABLED'),
    (413::bigint, 410::bigint, 'order:export',     '导出订单',     'BUTTON', NULL,            NULL,                 NULL,          3, true,  'ENABLED'),

    -- ===== 库存：出入库单（挂在商品中心下的库存菜单旁；parent=200）=====
    (310::bigint, 200::bigint, 'in-out',           '出入库单',     'MENU',   '/in-out',       'inventory/in-out',   'Sort',        6, true,  'ENABLED'),
    (311::bigint, 310::bigint, 'stock:io',         '出入库操作',   'BUTTON', NULL,            NULL,                 NULL,          1, true,  'ENABLED')
) AS v(id, parent_id, code, name, type, path, component, icon, sort, visible, status)
WHERE NOT EXISTS (SELECT 1 FROM identity.permission p WHERE p.id = v.id OR p.code = v.code);

-- 新权限赋给超级管理员
INSERT INTO identity.role_permission (role_id, permission_id)
SELECT 1, p.id
FROM identity.permission p
WHERE p.id IN (
    600, 610, 611, 612,
    700, 710, 711, 720, 721,
    430, 431, 432,
    412, 413,
    310, 311
)
AND NOT EXISTS (
    SELECT 1 FROM identity.role_permission rp
    WHERE rp.role_id = 1 AND rp.permission_id = p.id
);
