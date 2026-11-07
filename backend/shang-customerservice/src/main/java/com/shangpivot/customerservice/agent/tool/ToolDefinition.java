package com.shangpivot.customerservice.agent.tool;

import com.shangpivot.customerservice.agent.contract.AgentName;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 工具契约：模型/编排器只能通过该定义调用工具，不能拼 SQL、不能任意发 HTTP。
 *
 * @param name            工具名（全局唯一）
 * @param description     给模型看的能力说明
 * @param risk            风险级别
 * @param allowedAgents   允许调用该工具的子 Agent
 * @param timeout         超时；读 1s、写 3s 为默认
 * @param idempotent      写工具是否要求幂等键
 * @param inputSchema     简化 JSON Schema（required/properties），用于参数校验与提示词
 */
public record ToolDefinition(
        String name,
        String description,
        ToolRisk risk,
        Set<AgentName> allowedAgents,
        Duration timeout,
        boolean idempotent,
        Map<String, Object> inputSchema) {

    public ToolDefinition {
        allowedAgents = allowedAgents == null ? Set.of() : Set.copyOf(allowedAgents);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    public boolean allows(AgentName agent) {
        return allowedAgents.isEmpty() || allowedAgents.contains(agent);
    }
}
