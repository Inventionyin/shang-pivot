-- 订单事件日志：MQ 消费幂等落库（订单支付成功事件）
--
-- 唯一键 (order_id, event_type) 保证重复投递只落一行；消费者捕获 DuplicateKey 后直接确认。
CREATE TABLE IF NOT EXISTS commerce.order_event_log (
    id          BIGINT PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    event_type  VARCHAR(64)  NOT NULL,
    payload     JSONB,
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ  DEFAULT now(),
    updated_at  TIMESTAMPTZ  DEFAULT now(),
    deleted     BOOLEAN      DEFAULT FALSE,
    CONSTRAINT uk_order_event UNIQUE (order_id, event_type)
);

CREATE INDEX IF NOT EXISTS idx_order_event_created
    ON commerce.order_event_log (created_at DESC);
