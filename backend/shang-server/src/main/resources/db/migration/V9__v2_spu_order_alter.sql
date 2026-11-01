-- ============================================================
-- V9: SPU 销量/定时上架；订单来源端/脱敏手机/幂等键
-- 依据 docs/04-architecture/v2.0/数据模型.md §2
-- ============================================================

ALTER TABLE commerce.spu
    ADD COLUMN IF NOT EXISTS sold_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS schedule_on_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_spu_schedule_on
    ON commerce.spu (schedule_on_at)
    WHERE schedule_on_at IS NOT NULL AND deleted = FALSE;

COMMENT ON COLUMN commerce.spu.sold_count IS '支付成功累加销量；列表「N 人付款」';
COMMENT ON COLUMN commerce.spu.schedule_on_at IS '定时上架时间；Job 扫描后清空';

ALTER TABLE commerce."order"
    ADD COLUMN IF NOT EXISTS client_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS customer_phone_mask VARCHAR(32),
    ADD COLUMN IF NOT EXISTS idempotent_key VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_order_client_type
    ON commerce."order" (client_type) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_order_customer_created
    ON commerce."order" (customer_id, created_at DESC) WHERE deleted = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_customer_idempotent
    ON commerce."order" (customer_id, idempotent_key)
    WHERE idempotent_key IS NOT NULL AND deleted = FALSE;

COMMENT ON COLUMN commerce."order".client_type IS '下单端：web / h5 / mp';
COMMENT ON COLUMN commerce."order".customer_phone_mask IS '下单时脱敏手机，便于后台尾号筛选';
COMMENT ON COLUMN commerce."order".idempotent_key IS '客户维度幂等键，防重复下单';
