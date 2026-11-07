package com.shangpivot.customerservice.agent.graph;

import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期上下文（SSE emitter / 会话 / 用户消息）不进入图状态，避免 checkpoint 序列化失败。
 */
@Component
public class RunContextRegistry {

    public record RunContext(SseEmitter emitter, ChatSession session, CsMessage userMessage) {
    }

    private final Map<Long, RunContext> contexts = new ConcurrentHashMap<>();

    public void put(Long runId, RunContext context) {
        contexts.put(runId, context);
    }

    public RunContext get(Long runId) {
        return runId == null ? null : contexts.get(runId);
    }

    public void remove(Long runId) {
        if (runId != null) {
            contexts.remove(runId);
        }
    }
}
