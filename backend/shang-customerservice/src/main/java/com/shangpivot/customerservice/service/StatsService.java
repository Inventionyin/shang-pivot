package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.RetrievalTrace;
import com.shangpivot.customerservice.agent.trace.entity.ToolCall;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.agent.trace.mapper.RetrievalTraceMapper;
import com.shangpivot.customerservice.agent.trace.mapper.ToolCallMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.entity.CsTicket;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 服务看板（PRD §8.12）：会话量 / AI 应答 / 转人工率 / 工单量。 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final ChatSessionMapper sessionMapper;
    private final CsTicketMapper ticketMapper;
    private final com.shangpivot.customerservice.mapper.CsAuditLogMapper auditLogMapper;
    private final AgentRunMapper agentRunMapper;
    private final ToolCallMapper toolCallMapper;
    private final RetrievalTraceMapper retrievalTraceMapper;
    private final PendingActionMapper pendingActionMapper;
    private final CsMessageMapper messageMapper;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> dashboard(String range) {
        OffsetDateTime from = "week".equals(range)
                ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(7)
                : OffsetDateTime.of(LocalDate.now(), java.time.LocalTime.MIN, ZoneOffset.UTC);

        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .ge(ChatSession::getStartedAt, from));
        long total = sessions.size();
        long escalated = sessions.stream()
                .filter(s -> s.getSeatId() != null || ChatSession.STATUS_QUEUED.equals(s.getStatus()))
                .count();
        long humanHandled = sessions.stream()
                .filter(s -> s.getSeatId() != null)
                .count();
        double avgMinutes = sessions.stream()
                .filter(s -> s.getEndedAt() != null && s.getStartedAt() != null)
                .mapToLong(s -> java.time.Duration.between(s.getStartedAt(), s.getEndedAt()).toMinutes())
                .average().orElse(0);

        long ticketOpen = ticketMapper.selectCount(new LambdaQueryWrapper<CsTicket>()
                .ge(CsTicket::getCreatedAt, from).eq(CsTicket::getStatus, CsTicket.STATUS_OPEN));
        long ticketResolved = ticketMapper.selectCount(new LambdaQueryWrapper<CsTicket>()
                .ge(CsTicket::getCreatedAt, from).eq(CsTicket::getStatus, CsTicket.STATUS_RESOLVED));

        // LLM 消耗/稳定性：今日 ai_reply 事件聚合（token 用量 + 降级率）
        var replies = auditLogMapper.selectList(new LambdaQueryWrapper<com.shangpivot.customerservice.entity.CsAuditLog>()
                .ge(com.shangpivot.customerservice.entity.CsAuditLog::getCreatedAt, from)
                .in(com.shangpivot.customerservice.entity.CsAuditLog::getEvent, "ai_reply", "degraded_reply"));
        long aiReplies = replies.stream()
                .filter(a -> "ai_reply".equals(a.getEvent())).count();
        long degraded = replies.stream()
                .filter(a -> "degraded_reply".equals(a.getEvent())).count();
        long totalTokens = 0;
        for (var a : replies) {
            if ("ai_reply".equals(a.getEvent()) && a.getDetail() != null) {
                Object tokens = a.getDetail().get("tokens");
                if (tokens instanceof Map<?, ?> t) {
                    Object p = t.get("prompt");
                    Object c = t.get("completion");
                    if (p instanceof Number pn) {
                        totalTokens += pn.longValue();
                    }
                    if (c instanceof Number cn) {
                        totalTokens += cn.longValue();
                    }
                }
            }
        }

        // V3.1 Agent 运行指标：成功率 / P95 / 工具错误率 / 转人工率 / 检索无命中率
        List<AgentRun> runs = agentRunMapper.selectList(new LambdaQueryWrapper<AgentRun>()
                .ge(AgentRun::getStartedAt, from));
        long agentRuns = runs.size();
        long okRuns = runs.stream().filter(r -> "OK".equals(r.getStatus())).count();
        long handoffRuns = runs.stream().filter(r -> "HANDOFF".equals(r.getStatus())).count();
        List<Long> latencies = runs.stream()
                .filter(r -> r.getFinishedAt() != null && r.getStartedAt() != null)
                .map(r -> java.time.Duration.between(r.getStartedAt(), r.getFinishedAt()).toMillis())
                .sorted()
                .toList();
        long p95LatencyMs = 0;
        if (!latencies.isEmpty()) {
            int index = (int) Math.ceil(latencies.size() * 0.95) - 1;
            p95LatencyMs = latencies.get(Math.max(0, Math.min(index, latencies.size() - 1)));
        }
        Long toolTotalValue = toolCallMapper.selectCount(new LambdaQueryWrapper<ToolCall>()
                .ge(ToolCall::getCreatedAt, from));
        Long toolFailValue = toolCallMapper.selectCount(new LambdaQueryWrapper<ToolCall>()
                .ge(ToolCall::getCreatedAt, from).ne(ToolCall::getStatus, "SUCCESS"));
        long toolTotal = toolTotalValue == null ? 0 : toolTotalValue;
        long toolFail = toolFailValue == null ? 0 : toolFailValue;
        Long retrievalTotalValue = retrievalTraceMapper.selectCount(new LambdaQueryWrapper<RetrievalTrace>()
                .ge(RetrievalTrace::getCreatedAt, from));
        Long retrievalMissValue = retrievalTraceMapper.selectCount(new LambdaQueryWrapper<RetrievalTrace>()
                .ge(RetrievalTrace::getCreatedAt, from).eq(RetrievalTrace::getHit, false));
        long retrievalTotal = retrievalTotalValue == null ? 0 : retrievalTotalValue;
        long retrievalMiss = retrievalMissValue == null ? 0 : retrievalMissValue;

        Map<String, Object> m = new HashMap<>();
        m.put("range", "week".equals(range) ? "week" : "today");
        m.put("sessionTotal", total);
        m.put("escalated", escalated);
        m.put("escalateRate", total == 0 ? 0 : Math.round(escalated * 1000.0 / total) / 10.0);
        m.put("humanHandled", humanHandled);
        m.put("aiOnly", total - humanHandled);
        m.put("avgSessionMinutes", Math.round(avgMinutes * 10.0) / 10.0);
        m.put("ticketOpen", ticketOpen);
        m.put("ticketResolved", ticketResolved);
        m.put("aiReplies", aiReplies);
        m.put("degraded", degraded);
        m.put("degradedRate", aiReplies + degraded == 0 ? 0
                : Math.round(degraded * 1000.0 / (aiReplies + degraded)) / 10.0);
        m.put("totalTokens", totalTokens);
        m.put("agentRuns", agentRuns);
        m.put("agentSuccessRate", agentRuns == 0 ? 0
                : Math.round(okRuns * 1000.0 / agentRuns) / 10.0);
        m.put("p95LatencyMs", p95LatencyMs);
        m.put("toolErrorRate", toolTotal == 0 ? 0
                : Math.round(toolFail * 1000.0 / toolTotal) / 10.0);
        m.put("handoffRate", agentRuns == 0 ? 0
                : Math.round(handoffRuns * 1000.0 / agentRuns) / 10.0);
        m.put("retrievalMissRate", retrievalTotal == 0 ? 0
                : Math.round(retrievalMiss * 1000.0 / retrievalTotal) / 10.0);
        return m;
    }

    /** 指代加购健康：失败率、加购成功率、反问率、检索命中率。 */
    public Map<String, Object> referenceHealth(int days) {
        int window = Math.max(1, Math.min(days, 90));
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(window);
        long runs = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .ge(AgentRun::getStartedAt, from));
        long failed = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .ge(AgentRun::getStartedAt, from).eq(AgentRun::getStatus, "FAILED"));
        long handoff = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .ge(AgentRun::getStartedAt, from).eq(AgentRun::getStatus, "HANDOFF"));
        long confirm = agentRunMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .ge(AgentRun::getStartedAt, from).eq(AgentRun::getStatus, "NEED_CONFIRMATION"));
        long cartCalls = toolCallMapper.selectCount(new LambdaQueryWrapper<ToolCall>()
                .ge(ToolCall::getCreatedAt, from).eq(ToolCall::getToolName, "addToCart"));
        long cartSuccess = toolCallMapper.selectCount(new LambdaQueryWrapper<ToolCall>()
                .ge(ToolCall::getCreatedAt, from).eq(ToolCall::getToolName, "addToCart")
                .eq(ToolCall::getStatus, "SUCCESS"));
        long cartPending = pendingActionMapper.selectCount(
                new LambdaQueryWrapper<com.shangpivot.customerservice.agent.trace.entity.PendingAction>()
                        .ge(com.shangpivot.customerservice.agent.trace.entity.PendingAction::getCreatedAt, from)
                        .eq(com.shangpivot.customerservice.agent.trace.entity.PendingAction::getToolName, "addToCart"));
        long retrievals = retrievalTraceMapper.selectCount(new LambdaQueryWrapper<RetrievalTrace>()
                .ge(RetrievalTrace::getCreatedAt, from));
        long miss = retrievalTraceMapper.selectCount(new LambdaQueryWrapper<RetrievalTrace>()
                .ge(RetrievalTrace::getCreatedAt, from).eq(RetrievalTrace::getHit, false));
        long contextAsk = messageMapper.selectCount(new LambdaQueryWrapper<CsMessage>()
                .ge(CsMessage::getCreatedAt, from)
                .eq(CsMessage::getRole, CsMessage.ROLE_ASSISTANT)
                .like(CsMessage::getContent, "您指的是哪一款"));
        long skuAsk = messageMapper.selectCount(new LambdaQueryWrapper<CsMessage>()
                .ge(CsMessage::getCreatedAt, from)
                .eq(CsMessage::getRole, CsMessage.ROLE_ASSISTANT)
                .like(CsMessage::getContent, "有多个规格"));

        Map<String, Object> m = new HashMap<>();
        m.put("days", window);
        m.put("runs", runs);
        m.put("failed", failed);
        m.put("handoff", handoff);
        m.put("needConfirmation", confirm);
        m.put("cartCalls", cartCalls);
        m.put("cartSuccess", cartSuccess);
        m.put("cartPending", cartPending);
        m.put("retrievals", retrievals);
        m.put("retrievalMiss", miss);
        m.put("contextAsk", contextAsk);
        m.put("skuAsk", skuAsk);
        m.put("failedRate", rate(failed, runs));
        m.put("handoffRate", rate(handoff, runs));
        m.put("cartSuccessRate", rate(cartSuccess, cartCalls));
        m.put("retrievalMissRate", rate(miss, retrievals));
        m.put("contextAskRate", rate(contextAsk, runs));
        return m;
    }

    /** 按天趋势：运行/失败/转人工、加购调用、指代反问、检索无命中。 */
    public List<Map<String, Object>> referenceTrend(int days) {
        int window = Math.max(1, Math.min(days, 90));
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime from = today.minusDays(window - 1L).atStartOfDay(zone).toOffsetDateTime();
        Timestamp ts = Timestamp.from(from.toInstant());
        String runSql = """
                SELECT to_char(started_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       count(*) AS runs,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'HANDOFF') AS handoff
                FROM customerservice.agent_run
                WHERE started_at >= ?
                GROUP BY 1
                """;
        String cartSql = """
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       count(*) AS cart_calls,
                       count(*) FILTER (WHERE status = 'SUCCESS') AS cart_success
                FROM customerservice.tool_call
                WHERE created_at >= ? AND tool_name = 'addToCart'
                GROUP BY 1
                """;
        String retrievalSql = """
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       count(*) AS retrievals,
                       count(*) FILTER (WHERE hit = FALSE) AS retrieval_miss
                FROM customerservice.retrieval
                WHERE created_at >= ?
                GROUP BY 1
                """;
        String askSql = """
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       count(*) FILTER (WHERE content LIKE '%您指的是哪一款%') AS context_ask,
                       count(*) FILTER (WHERE content LIKE '%有多个规格%') AS sku_ask
                FROM customerservice.message
                WHERE created_at >= ? AND role = 'ASSISTANT'
                GROUP BY 1
                """;
        Map<String, Map<String, Object>> byDay = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(runSql, ts)) {
            byDay.computeIfAbsent(String.valueOf(row.get("day")), k -> new java.util.LinkedHashMap<>())
                    .putAll(row);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(cartSql, ts)) {
            byDay.computeIfAbsent(String.valueOf(row.get("day")), k -> new java.util.LinkedHashMap<>())
                    .putAll(row);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(retrievalSql, ts)) {
            byDay.computeIfAbsent(String.valueOf(row.get("day")), k -> new java.util.LinkedHashMap<>())
                    .putAll(row);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(askSql, ts)) {
            byDay.computeIfAbsent(String.valueOf(row.get("day")), k -> new java.util.LinkedHashMap<>())
                    .putAll(row);
        }
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_DATE;
        for (int i = 0; i < window; i++) {
            String day = today.minusDays(window - 1L - i).format(fmt);
            Map<String, Object> row = byDay.getOrDefault(day, Map.of());
            Map<String, Object> point = new java.util.LinkedHashMap<>();
            point.put("date", day);
            point.put("runs", number(row.get("runs")));
            point.put("failed", number(row.get("failed")));
            point.put("handoff", number(row.get("handoff")));
            point.put("cartCalls", number(row.get("cart_calls")));
            point.put("cartSuccess", number(row.get("cart_success")));
            point.put("retrievals", number(row.get("retrievals")));
            point.put("retrievalMiss", number(row.get("retrieval_miss")));
            point.put("contextAsk", number(row.get("context_ask")));
            point.put("skuAsk", number(row.get("sku_ask")));
            trend.add(point);
        }
        return trend;
    }

    /** 答案质量：点赞/点踩、点踩原因分布。 */
    public Map<String, Object> answerQuality(int days) {
        int window = Math.max(1, Math.min(days, 90));
        Timestamp from = Timestamp.valueOf(java.time.LocalDateTime.now()
                .minusDays(window - 1L).toLocalDate().atStartOfDay());
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE rating = 'UP') AS up,
                       count(*) FILTER (WHERE rating = 'DOWN') AS down
                FROM customerservice.message_feedback
                WHERE deleted = FALSE AND created_at >= ?
                """, from);
        long total = number(totals.get("total"));
        long up = number(totals.get("up"));
        long down = number(totals.get("down"));

        List<Map<String, Object>> reasons = new java.util.ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT COALESCE(NULLIF(reason, ''), '未填写') AS reason, count(*) AS count
                FROM customerservice.message_feedback
                WHERE deleted = FALSE AND rating = 'DOWN' AND created_at >= ?
                GROUP BY 1 ORDER BY count DESC
                """, from)) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("reason", row.get("reason"));
            item.put("count", number(row.get("count")));
            reasons.add(item);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("days", window);
        result.put("total", total);
        result.put("up", up);
        result.put("down", down);
        result.put("upRate", rate(up, total));
        result.put("downRate", rate(down, total));
        result.put("reasons", reasons);
        return result;
    }

    /** 答案质量趋势：按天返回点赞/点踩。 */
    public List<Map<String, Object>> answerQualityTrend(int days) {
        int window = Math.max(1, Math.min(days, 90));
        Timestamp from = Timestamp.valueOf(java.time.LocalDateTime.now()
                .minusDays(window - 1L).toLocalDate().atStartOfDay());
        Map<String, Map<String, Object>> byDay = new java.util.HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       count(*) AS total,
                       count(*) FILTER (WHERE rating = 'UP') AS up,
                       count(*) FILTER (WHERE rating = 'DOWN') AS down
                FROM customerservice.message_feedback
                WHERE deleted = FALSE AND created_at >= ?
                GROUP BY 1
                """, from)) {
            byDay.put(String.valueOf(row.get("day")), row);
        }
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 0; i < window; i++) {
            String day = today.minusDays(window - 1L - i).format(DateTimeFormatter.ISO_DATE);
            Map<String, Object> row = byDay.getOrDefault(day, Map.of());
            long total = number(row.get("total"));
            long up = number(row.get("up"));
            long down = number(row.get("down"));
            Map<String, Object> point = new java.util.LinkedHashMap<>();
            point.put("date", day);
            point.put("total", total);
            point.put("up", up);
            point.put("down", down);
            point.put("downRate", rate(down, total));
            trend.add(point);
        }
        return trend;
    }

    /** 最近点踩明细（管理端质量复核用）。 */
    public List<Map<String, Object>> recentNegativeFeedback(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.queryForList("""
                SELECT f.id, f.message_id, f.session_id, f.rating, f.reason, f.comment,
                       f.created_at, m.content AS answer, a.id AS run_id
                FROM customerservice.message_feedback f
                LEFT JOIN customerservice.message m ON m.id = f.message_id
                LEFT JOIN LATERAL (
                    SELECT id FROM customerservice.agent_run
                    WHERE assistant_message_id = f.message_id
                    ORDER BY id DESC LIMIT 1
                ) a ON TRUE
                WHERE f.deleted = FALSE AND f.rating = 'DOWN'
                ORDER BY f.created_at DESC
                LIMIT ?
                """, size);
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}
