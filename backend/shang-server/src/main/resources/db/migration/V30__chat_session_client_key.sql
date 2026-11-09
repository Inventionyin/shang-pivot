-- 会话分端隔离 + 空闲判定兜底索引（V3.3.1）
-- client_key 由 C 端浏览器生成并持久化，用于区分同一账号的不同浏览器/设备；
-- 空值表示未提供（平台自动化测试、脚本客户端），沿用原有的同账号复用语义。

ALTER TABLE customerservice.chat_session
    ADD COLUMN IF NOT EXISTS client_key VARCHAR(64) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_cs_session_client
    ON customerservice.chat_session (customer_id, client_key, status, id DESC);

-- 空闲调度兜底：即使应用侧 last_message_at 刷新失败，也能按消息真实时间判断。
CREATE INDEX IF NOT EXISTS idx_cs_message_session_created
    ON customerservice.message (session_id, created_at);
