-- ============================================================
-- V3.1 Agent runtime：运行轨迹、LLM/工具/检索埋点、写操作确认
-- 设计来源：docs/04-architecture/adr/0007-agent-runtime.md
-- 只新增表，不改 V3.0 已有表，保证旧编排器可回滚。
-- ============================================================

-- 一次用户任务（一轮用户消息触发的一次 Agent 运行）
CREATE TABLE IF NOT EXISTS customerservice.agent_run (
    id                   BIGINT PRIMARY KEY,
    trace_id             VARCHAR(64),
    session_id           BIGINT       NOT NULL,
    assistant_message_id BIGINT,
    customer_id          BIGINT       NOT NULL,
    channel              VARCHAR(32),
    intent               VARCHAR(32),
    agent                VARCHAR(64),
    status               VARCHAR(32)  NOT NULL,          -- RUNNING/OK/NEED_CONFIRMATION/HANDOFF/FAILED
    started_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at          TIMESTAMPTZ,
    total_tokens         BIGINT       NOT NULL DEFAULT 0,
    total_cost           NUMERIC(12,6),
    error                TEXT,
    model                VARCHAR(128),
    prompt_version       VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_cs_run_session ON customerservice.agent_run (session_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_cs_run_status ON customerservice.agent_run (status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_cs_run_trace ON customerservice.agent_run (trace_id);

-- 图节点/子 Agent 步骤
CREATE TABLE IF NOT EXISTS customerservice.agent_step (
    id                BIGINT PRIMARY KEY,
    run_id            BIGINT       NOT NULL,
    parent_step_id    BIGINT,
    node              VARCHAR(64)  NOT NULL,
    agent             VARCHAR(64),
    status            VARCHAR(32)  NOT NULL,
    input_summary     TEXT,
    output_summary    TEXT,
    latency_ms        INT,
    checkpoint_id     VARCHAR(128),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_step_run ON customerservice.agent_step (run_id, id);

-- 一次模型调用（流式结束后聚合为一行，不按 token 落库）
CREATE TABLE IF NOT EXISTS customerservice.llm_call (
    id             BIGINT PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    step_id        BIGINT,
    model          VARCHAR(128),
    prompt_version VARCHAR(64),
    input_tokens   INT          NOT NULL DEFAULT 0,
    output_tokens  INT          NOT NULL DEFAULT 0,
    latency_ms     INT,
    finish_reason  VARCHAR(32),
    status         VARCHAR(32)  NOT NULL,
    request_ref    TEXT,                                -- 脱敏/截断后的 prompt 摘要或对象存储 key
    response_ref   TEXT,                                -- 脱敏/截断后的响应摘要或对象存储 key
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_llm_run ON customerservice.llm_call (run_id, id);

-- 一次工具调用
CREATE TABLE IF NOT EXISTS customerservice.tool_call (
    id              BIGINT PRIMARY KEY,
    run_id          BIGINT       NOT NULL,
    step_id         BIGINT,
    tool_name       VARCHAR(64)  NOT NULL,
    args_json       JSONB,
    result_json     JSONB,
    status          VARCHAR(32)  NOT NULL,               -- SUCCESS/FAILED/TIMEOUT/DENIED
    latency_ms      INT,
    retry_count     INT          NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128),
    error_code      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_tool_run ON customerservice.tool_call (run_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_tool_idem ON customerservice.tool_call (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND status IN ('RUNNING', 'SUCCESS');

-- 一次 RAG 检索
CREATE TABLE IF NOT EXISTS customerservice.retrieval (
    id         BIGINT PRIMARY KEY,
    run_id     BIGINT       NOT NULL,
    query      TEXT,
    top_k      INT,
    doc_ids    JSONB,
    scores     JSONB,
    latency_ms INT,
    hit        BOOLEAN      NOT NULL DEFAULT FALSE,
    backend    VARCHAR(32),                              -- vector/lexical/context
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_retrieval_run ON customerservice.retrieval (run_id, id);

-- 护栏事件（注入/敏感输出/越权/限流等）
CREATE TABLE IF NOT EXISTS customerservice.guardrail_event (
    id         BIGINT PRIMARY KEY,
    run_id     BIGINT,
    type       VARCHAR(64) NOT NULL,
    action     VARCHAR(32) NOT NULL,                     -- BLOCK/MASK/WARN/ESCALATE
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_guard_run ON customerservice.guardrail_event (run_id, id);

-- 写操作待确认动作（模型只提计划，用户确认后才执行）
CREATE TABLE IF NOT EXISTS customerservice.pending_action (
    id              BIGINT PRIMARY KEY,
    session_id      BIGINT       NOT NULL,
    run_id          BIGINT,
    customer_id     BIGINT       NOT NULL,
    tool_name       VARCHAR(64)  NOT NULL,
    args_json       JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING/CONFIRMED/CANCELLED/EXECUTED/EXPIRED
    idempotency_key VARCHAR(128) NOT NULL,
    result_json     JSONB,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_cs_pending_session ON customerservice.pending_action (session_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cs_pending_idem ON customerservice.pending_action (idempotency_key);

-- 用户反馈（点赞/点踩/转人工/解决），P1 使用
CREATE TABLE IF NOT EXISTS customerservice.feedback (
    id          BIGINT PRIMARY KEY,
    run_id      BIGINT,
    session_id  BIGINT,
    customer_id BIGINT,
    type        VARCHAR(32) NOT NULL,
    score       INT,
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cs_feedback_run ON customerservice.feedback (run_id);
