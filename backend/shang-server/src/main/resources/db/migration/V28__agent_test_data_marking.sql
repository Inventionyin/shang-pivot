-- ============================================================
-- 测试流量标记 + 平台真实数据 webhook outbox
-- 1) 平台自动化测试/人工体验请求带 X-Agent-Test 头，测试会话与运行单独标记
-- 2) 生产会话通过 outbox 异步推送到 AI 测试平台，避免测试/真实数据混算
-- ============================================================

ALTER TABLE customerservice.chat_session
    ADD COLUMN IF NOT EXISTS is_test BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS test_source VARCHAR(20) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS test_run_id VARCHAR(64) NOT NULL DEFAULT '';

COMMENT ON COLUMN customerservice.chat_session.is_test IS '平台测试流量（自动化/人工体验），不计入真实生产指标';
COMMENT ON COLUMN customerservice.chat_session.test_source IS 'ai=平台自动化测试；human=平台人工体验';
COMMENT ON COLUMN customerservice.chat_session.test_run_id IS '平台侧 runId，用于链路关联';

ALTER TABLE customerservice.agent_run
    ADD COLUMN IF NOT EXISTS is_test BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS test_source VARCHAR(20) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS test_run_id VARCHAR(64) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_cs_session_test ON customerservice.chat_session (is_test, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_cs_run_test ON customerservice.agent_run (is_test, started_at DESC);

-- Webhook outbox：与业务同事务写入，定时异步投递，失败指数退避重试
CREATE TABLE IF NOT EXISTS customerservice.platform_outbox (
    id            BIGINT PRIMARY KEY,
    event_id      VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload_json  JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING/SENDING/SENT/FAILED
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_outbox_event ON customerservice.platform_outbox (event_id);
CREATE INDEX IF NOT EXISTS idx_cs_outbox_pending ON customerservice.platform_outbox (status, next_retry_at);
