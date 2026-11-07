package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.RetrievalTrace;
import com.shangpivot.customerservice.agent.trace.entity.ToolCall;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.agent.trace.mapper.RetrievalTraceMapper;
import com.shangpivot.customerservice.agent.trace.mapper.ToolCallMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsTicket;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
}
