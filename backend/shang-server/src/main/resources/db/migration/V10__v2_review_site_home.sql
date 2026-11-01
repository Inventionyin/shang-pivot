-- ============================================================
-- V10: 评价 / 站点品牌 / 首页运营位 + 预置槽位
-- 依据 docs/04-architecture/v2.0/数据模型.md §4–§5
-- ============================================================

-- ---------- review ----------
CREATE TABLE IF NOT EXISTS commerce.review (
    id              BIGINT PRIMARY KEY,
    order_item_id   BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    spu_id          BIGINT       NOT NULL,
    rating          SMALLINT     NOT NULL,
    content         TEXT,
    pics            JSONB,
    status          VARCHAR(16)  NOT NULL DEFAULT 'VISIBLE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_order_item
    ON commerce.review (order_item_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_review_spu_status
    ON commerce.review (spu_id, status) WHERE deleted = FALSE;

COMMENT ON TABLE commerce.review IS '订单项评价；发表即 VISIBLE，后台可 HIDDEN';

-- ---------- site_brand（单行配置，id=1） ----------
CREATE TABLE IF NOT EXISTS commerce.site_brand (
    id              BIGINT PRIMARY KEY,
    logo_key        VARCHAR(512),
    name            VARCHAR(64)  NOT NULL DEFAULT '尚品汇',
    slogan          VARCHAR(128),
    favicon_key     VARCHAR(512),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    trace_id        VARCHAR(64)
);

INSERT INTO commerce.site_brand (id, logo_key, name, slogan)
SELECT 1, NULL, '尚品汇', '选好物，享生活'
WHERE NOT EXISTS (SELECT 1 FROM commerce.site_brand WHERE id = 1);

COMMENT ON TABLE commerce.site_brand IS '站点品牌（Logo/名称/Slogan）；推荐单行 id=1';

-- ---------- home_slot / home_slot_item ----------
CREATE TABLE IF NOT EXISTS commerce.home_slot (
    id              BIGINT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    title           VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ON',
    sort            INT          NOT NULL DEFAULT 0,
    valid_from      TIMESTAMPTZ,
    valid_to        TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_home_slot_code ON commerce.home_slot (code);

CREATE TABLE IF NOT EXISTS commerce.home_slot_item (
    id              BIGINT PRIMARY KEY,
    slot_id         BIGINT       NOT NULL,
    sort            INT          NOT NULL DEFAULT 0,
    spu_id          BIGINT,
    image_key       VARCHAR(512),
    link_type       VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    link_value      VARCHAR(512),
    title           VARCHAR(128),
    extra           JSONB,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ON'
);
CREATE INDEX IF NOT EXISTS idx_home_slot_item_slot ON commerce.home_slot_item (slot_id, sort);

COMMENT ON TABLE commerce.home_slot IS '首页运营位槽位';
COMMENT ON TABLE commerce.home_slot_item IS '运营位内容项（Banner/选品/热搜词）';

-- 预置槽位
INSERT INTO commerce.home_slot (id, code, title, status, sort)
SELECT * FROM (VALUES
    (1::bigint, 'home_banner',        '首页 Banner',  'ON', 1),
    (2::bigint, 'home_promo_subsidy', '百亿补贴',     'ON', 2),
    (3::bigint, 'home_promo_flash',   '限时秒杀',     'ON', 3),
    (4::bigint, 'home_promo_live',    '直播入口',     'ON', 4),
    (5::bigint, 'home_hot_keywords',  '热搜词',       'ON', 5),
    (6::bigint, 'home_hot_spus',      '热门精选',     'ON', 6)
) AS v(id, code, title, status, sort)
WHERE NOT EXISTS (SELECT 1 FROM commerce.home_slot s WHERE s.id = v.id OR s.code = v.code);
