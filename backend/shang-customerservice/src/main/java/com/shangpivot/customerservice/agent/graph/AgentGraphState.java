package com.shangpivot.customerservice.agent.graph;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/** LangGraph4j 图状态：只放可序列化的 ID/文本，SSE emitter 放在 RunContextRegistry。 */
public class AgentGraphState extends AgentState {

    public AgentGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public Long runId() {
        return value("runId").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public Long sessionId() {
        return value("sessionId").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public Long customerId() {
        return value("customerId").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public String userText() {
        return value("userText").map(String::valueOf).orElse("");
    }

    public SupervisorAgent.RouteResult route() {
        return value("route").map(SupervisorAgent.RouteResult.class::cast).orElse(null);
    }

    public String facts() {
        return value("facts").map(String::valueOf).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> cards() {
        return value("cards").map(v -> (List<Map<String, Object>>) v).orElse(List.of());
    }

    public String status() {
        return value("status").map(String::valueOf).orElse("OK");
    }

    public String answer() {
        return value("answer").map(String::valueOf).orElse("");
    }

    public String displayAnswer() {
        return value("displayAnswer").map(String::valueOf).orElse("");
    }

    public String reasoning() {
        return value("reasoning").map(String::valueOf).orElse("");
    }

    public Long pendingActionId() {
        return value("pendingActionId").map(v -> ((Number) v).longValue()).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public List<String> toolNames() {
        return value("toolNames").map(v -> (List<String>) v).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public List<String> sources() {
        return value("sources").map(v -> (List<String>) v).orElse(List.of());
    }

    public long totalTokens() {
        return value("totalTokens").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public long promptTokens() {
        return value("promptTokens").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public long completionTokens() {
        return value("completionTokens").map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public String sessionStatus() {
        return value("sessionStatus").map(String::valueOf).orElse("");
    }
}
