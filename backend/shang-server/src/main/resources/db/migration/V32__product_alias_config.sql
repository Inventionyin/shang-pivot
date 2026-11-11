-- V32: 品牌/系列别名配置表（与代码解耦，运营可增删）。
-- alias = 用户常用叫法；target_token = 商品名/副标题中的英文或系列词。
CREATE TABLE IF NOT EXISTS customerservice.product_alias (
    id           BIGINT PRIMARY KEY,
    alias        VARCHAR(64)  NOT NULL,
    target_token VARCHAR(128) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id     VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_product_alias
    ON customerservice.product_alias (alias, target_token);

INSERT INTO customerservice.product_alias (id, alias, target_token) VALUES
    (1,  '华为', 'huawei'),
    (2,  '小米', 'xiaomi'),
    (3,  '红米', 'redmi'),
    (4,  '苹果', 'iphone'),
    (5,  '苹果', 'macbook'),
    (6,  '苹果', 'airpods'),
    (7,  '苹果', 'apple watch'),
    (8,  '戴尔', 'dell'),
    (9,  '联想', 'thinkpad'),
    (10, '联想', 'lenovo'),
    (11, '联想', '拯救者'),
    (12, '索尼', 'sony'),
    (13, '戴森', 'dyson'),
    (14, '安克', 'anker'),
    (15, '海尔', 'haier'),
    (16, '美的', 'midea'),
    (17, '石头', 'roborock'),
    (18, '罗技', 'logitech'),
    (19, '荣耀', 'honor'),
    (20, 'OPPO', 'oppo'),
    (21, 'vivo', 'vivo')
ON CONFLICT (alias, target_token) DO NOTHING;
