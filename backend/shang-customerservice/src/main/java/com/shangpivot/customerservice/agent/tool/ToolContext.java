package com.shangpivot.customerservice.agent.tool;

import com.shangpivot.customerservice.agent.contract.AgentName;

/**
 * 工具执行上下文。customerId 必须由后端从登录态注入，不能由模型参数决定。
 */
public record ToolContext(
        Long customerId,
        Long sessionId,
        Long runId,
        String traceId,
        AgentName agent,
        boolean confirmed,
        String idempotencyKey) {

    public ToolContext withConfirmed(boolean value) {
        return new ToolContext(customerId, sessionId, runId, traceId, agent, value, idempotencyKey);
    }

    public ToolContext withIdempotencyKey(String key) {
        return new ToolContext(customerId, sessionId, runId, traceId, agent, confirmed, key);
    }
}
