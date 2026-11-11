-- V33: 答案点踩反馈（质量闭环）
CREATE TABLE IF NOT EXISTS customerservice.message_feedback (
    id BIGINT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reason VARCHAR(32),
    comment VARCHAR(500),
    run_id BIGINT,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_message_feedback
    ON customerservice.message_feedback (message_id, customer_id)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_cs_message_feedback_created
    ON customerservice.message_feedback (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_cs_message_feedback_rating
    ON customerservice.message_feedback (rating, created_at DESC);
