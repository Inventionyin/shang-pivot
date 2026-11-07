-- 评测候选样本导出（只读）。在 shang 库执行：
--   psql -d shang -f scripts/eval/export_candidates.sql
-- 导出的原文必须脱敏、人工复核后才能进入 GoldenDataset。
-- 说明：agent_run 目前没有 user_message_id，用户原话用“run 开始前最近一条 USER 消息”近似关联。

\echo '=== 1. 最近 graph 运行 + 用户原话（抽样） ==='
WITH recent AS (
    SELECT id, session_id, customer_id, intent, agent, status, started_at, total_tokens
    FROM customerservice.agent_run
    WHERE started_at > now() - interval '14 days'
    ORDER BY started_at DESC
    LIMIT 200
)
SELECT r.id AS run_id,
       r.intent,
       r.agent,
       r.status,
       r.total_tokens,
       (SELECT m.content
        FROM customerservice.message m
        WHERE m.session_id = r.session_id
          AND m.role = 'USER'
          AND m.created_at <= r.started_at
        ORDER BY m.created_at DESC
        LIMIT 1) AS user_text,
       (SELECT string_agg(t.tool_name || ':' || t.status, ',' ORDER BY t.id)
        FROM customerservice.tool_call t
        WHERE t.run_id = r.id) AS tools
FROM recent r
ORDER BY r.started_at DESC;

\echo '=== 2. 转人工 / 失败 / 待确认（优先入集） ==='
SELECT r.id, r.intent, r.agent, r.status, r.error,
       (SELECT m.content FROM customerservice.message m
        WHERE m.session_id = r.session_id AND m.role = 'USER'
          AND m.created_at <= r.started_at ORDER BY m.created_at DESC LIMIT 1) AS user_text
FROM customerservice.agent_run r
WHERE r.status IN ('HANDOFF', 'FAILED', 'NEED_CONFIRMATION')
ORDER BY r.started_at DESC
LIMIT 100;

\echo '=== 3. 工具失败 / 超时（排除预期 NOT_FOUND 前先人工判断） ==='
SELECT run_id, tool_name, status, error_code, args_json, left(result_json::text, 300) AS result
FROM customerservice.tool_call
WHERE status <> 'SUCCESS'
ORDER BY id DESC
LIMIT 100;

\echo '=== 4. 检索无命中（知识/商品） ==='
SELECT run_id, query, top_k, hit, backend, left(scores::text, 200) AS scores
FROM customerservice.retrieval
WHERE hit = FALSE
ORDER BY id DESC
LIMIT 100;

\echo '=== 5. 知识库原文（生成政策 QA 的种子） ==='
SELECT id, title, category, left(content, 1200) AS content
FROM customerservice.knowledge_doc
WHERE status = 'PUBLISHED' AND deleted = FALSE
ORDER BY updated_at DESC
LIMIT 50;

\echo '=== 6. 工单 / 售后原因（转成用户话术） ==='
SELECT 'ticket' AS source, type, title, left(remark, 500) AS reason
FROM customerservice.ticket
ORDER BY id DESC
LIMIT 50;

SELECT 'after_sale' AS source, type, left(reason, 500) AS reason
FROM commerce.after_sale
ORDER BY id DESC
LIMIT 50;
