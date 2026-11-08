-- 会话活跃时间与 30 分钟无新消息自动结束（V3.2 会话恢复）
-- 只新增列/索引，不改变已有数据语义；旧版本 jar 可忽略该列继续运行。

ALTER TABLE customerservice.chat_session
    ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ;

-- 历史会话按最后一条消息时间回填；没有消息则按会话创建时间回填。
UPDATE customerservice.chat_session s
SET last_message_at = COALESCE(
        (SELECT MAX(m.created_at)
           FROM customerservice.message m
          WHERE m.session_id = s.id
            AND m.deleted = FALSE),
        s.started_at,
        s.created_at,
        NOW())
WHERE s.last_message_at IS NULL;

ALTER TABLE customerservice.chat_session
    ALTER COLUMN last_message_at SET DEFAULT NOW();

ALTER TABLE customerservice.chat_session
    ALTER COLUMN last_message_at SET NOT NULL;

COMMENT ON COLUMN customerservice.chat_session.last_message_at IS '最后一条消息时间（用户或助手）；用于 30 分钟无新消息自动结束会话';

-- 调度扫描：只关心进行中的状态。
CREATE INDEX IF NOT EXISTS idx_cs_session_idle
    ON customerservice.chat_session (status, last_message_at)
    WHERE status IN ('AI_ONGOING', 'QUEUED', 'HUMAN_ONGOING');

-- 人工体验会话复用查询：customer + is_test + test_source + status。
CREATE INDEX IF NOT EXISTS idx_cs_session_human_test
    ON customerservice.chat_session (customer_id, is_test, test_source, status, id DESC)
    WHERE is_test = TRUE;
