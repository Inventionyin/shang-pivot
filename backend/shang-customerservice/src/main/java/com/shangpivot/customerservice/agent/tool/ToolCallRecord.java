package com.shangpivot.customerservice.agent.tool;

import java.util.Map;

/** 工具调用埋点（由 AgentTraceService 落库）。 */
public record ToolCallRecord(
        Long runId,
        Long stepId,
        String toolName,
        Map<String, Object> args,
        Object result,
        String status,
        long latencyMs,
        String idempotencyKey,
        String errorCode) {
}
