-- ============================================================
-- V8: V2 顾客 / 购物车 / 地址 / 收藏 / 足迹
-- 依据 docs/04-architecture/v2.0/数据模型.md §3–§4
-- ============================================================

-- ---------- identity.customer ----------
CREATE TABLE IF NOT EXISTS identity.customer (
    id              BIGINT PRIMARY KEY,
    openid          VARCHAR(64),
    union_id        VARCHAR(64),
    phone           VARCHAR(20),
    nickname        VARCHAR(64),
    avatar          VARCHAR(512),
    gender          SMALLINT     NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_phone
    ON identity.customer (phone) WHERE phone IS NOT NULL AND deleted = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_openid
    ON identity.customer (openid) WHERE openid IS NOT NULL AND deleted = FALSE;

COMMENT ON TABLE identity.customer IS 'C 端顾客账号（手机号登录；可扩展微信）';
COMMENT ON COLUMN identity.customer.phone IS '手机号，登录主键之一';
COMMENT ON COLUMN identity.customer.status IS 'NORMAL 正常 / FROZEN 冻结';
COMMENT ON COLUMN identity.customer.gender IS '0未知 / 1男 / 2女';

-- ---------- commerce.cart / cart_item ----------
CREATE TABLE IF NOT EXISTS commerce.cart (
    id              BIGINT PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cart_customer ON commerce.cart (customer_id);

CREATE TABLE IF NOT EXISTS commerce.cart_item (
    id              BIGINT PRIMARY KEY,
    cart_id         BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    quantity        INT          NOT NULL DEFAULT 1,
    selected        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_cart_item_qty CHECK (quantity >= 1)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cart_item_sku ON commerce.cart_item (cart_id, sku_id);

COMMENT ON TABLE commerce.cart IS '购物车（一顾客一车）';
COMMENT ON TABLE commerce.cart_item IS '购物车明细';

-- ---------- commerce.address ----------
CREATE TABLE IF NOT EXISTS commerce.address (
    id              BIGINT PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    province        VARCHAR(64)  NOT NULL,
    city            VARCHAR(64)  NOT NULL,
    district        VARCHAR(64)  NOT NULL,
    detail          VARCHAR(255) NOT NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_address_customer ON commerce.address (customer_id) WHERE deleted = FALSE;

COMMENT ON TABLE commerce.address IS '收货地址';

-- ---------- commerce.favorite / browse_history ----------
CREATE TABLE IF NOT EXISTS commerce.favorite (
    id              BIGINT PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    spu_id          BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_favorite_customer_spu ON commerce.favorite (customer_id, spu_id);

CREATE TABLE IF NOT EXISTS commerce.browse_history (
    id              BIGINT PRIMARY KEY,
    customer_id     BIGINT       NOT NULL,
    spu_id          BIGINT       NOT NULL,
    last_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_browse_customer_spu ON commerce.browse_history (customer_id, spu_id);
CREATE INDEX IF NOT EXISTS idx_browse_customer_last ON commerce.browse_history (customer_id, last_at DESC);

COMMENT ON TABLE commerce.favorite IS '商品收藏';
COMMENT ON TABLE commerce.browse_history IS '浏览足迹';
