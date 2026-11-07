package com.shangpivot.customerservice.agent.trace;

import com.shangpivot.customerservice.agent.tool.ToolCallRecord;
import com.shangpivot.customerservice.agent.tool.ToolCallRecorder;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.AgentStep;
import com.shangpivot.customerservice.agent.trace.entity.GuardrailEvent;
import com.shangpivot.customerservice.agent.trace.entity.LlmCall;
import com.shangpivot.customerservice.agent.trace.entity.RetrievalTrace;
import com.shangpivot.customerservice.agent.trace.entity.ToolCall;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.agent.trace.mapper.AgentStepMapper;
import com.shangpivot.customerservice.agent.trace.mapper.GuardrailEventMapper;
import com.shangpivot.customerservice.agent.trace.mapper.LlmCallMapper;
import com.shangpivot.customerservice.agent.trace.mapper.RetrievalTraceMapper;
import com.shangpivot.customerservice.agent.trace.mapper.ToolCallMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 运行轨迹落库。所有方法失败都不能影响主流程（调用方已兜底，这里再吞一次异常）。
 * 完整 prompt/response 只存脱敏、截断后的摘要；大对象后续可切 MinIO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService implements ToolCallRecorder {

    private static final int MAX_TEXT = 4000;

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final LlmCallMapper llmCallMapper;
    private final ToolCallMapper toolCallMapper;
    private final RetrievalTraceMapper retrievalMapper;
    private final GuardrailEventMapper guardrailMapper;

    public AgentRun startRun(Long sessionId, Long customerId, String channel,
                             String intent, String agent, String model, String promptVersion) {
        AgentRun run = new AgentRun();
        run.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        run.setSessionId(sessionId);
        run.setCustomerId(customerId);
        run.setChannel(channel);
        run.setIntent(intent);
        run.setAgent(agent);
        run.setStatus("RUNNING");
        run.setModel(model);
        run.setPromptVersion(promptVersion);
        run.setStartedAt(OffsetDateTime.now());
        run.setTotalTokens(0L);
        try {
            runMapper.insert(run);
            return run;
        } catch (Exception e) {
            log.warn("[cs] startRun trace failed: {}", e.getMessage());
            run.setId(0L);
            return run;
        }
    }

    public void finishRun(AgentRun run, String status, long totalTokens, String error, Long assistantMessageId) {
        if (run == null || run.getId() == null || run.getId() == 0L) {
            return;
        }
        try {
            run.setStatus(status);
            run.setTotalTokens(totalTokens);
            run.setError(truncate(error));
            run.setAssistantMessageId(assistantMessageId);
            run.setFinishedAt(OffsetDateTime.now());
            runMapper.updateById(run);
        } catch (Exception e) {
            log.warn("[cs] finishRun trace failed: {}", e.getMessage());
        }
    }

    public Long startStep(Long runId, Long parentStepId, String node, String agent, String inputSummary) {
        if (runId == null || runId == 0L) {
            return null;
        }
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setParentStepId(parentStepId);
        step.setNode(node);
        step.setAgent(agent);
        step.setStatus("RUNNING");
        step.setInputSummary(truncate(inputSummary));
        try {
            stepMapper.insert(step);
            return step.getId();
        } catch (Exception e) {
            log.warn("[cs] startStep trace failed: {}", e.getMessage());
            return null;
        }
    }

    public void finishStep(Long stepId, String status, String outputSummary, long latencyMs, String checkpointId) {
        if (stepId == null) {
            return;
        }
        try {
            AgentStep step = new AgentStep();
            step.setId(stepId);
            step.setStatus(status);
            step.setOutputSummary(truncate(outputSummary));
            step.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            step.setCheckpointId(checkpointId);
            stepMapper.updateById(step);
        } catch (Exception e) {
            log.warn("[cs] finishStep trace failed: {}", e.getMessage());
        }
    }

    public void recordLlmCall(Long runId, Long stepId, String model, String promptVersion,
                              int inputTokens, int outputTokens, long latencyMs,
                              String finishReason, String status, String request, String response) {
        if (runId == null || runId == 0L) {
            return;
        }
        try {
            LlmCall call = new LlmCall();
            call.setRunId(runId);
            call.setStepId(stepId);
            call.setModel(model);
            call.setPromptVersion(promptVersion);
            call.setInputTokens(inputTokens);
            call.setOutputTokens(outputTokens);
            call.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            call.setFinishReason(finishReason);
            call.setStatus(status);
            call.setRequestRef(truncate(request));
            call.setResponseRef(truncate(response));
            llmCallMapper.insert(call);
        } catch (Exception e) {
            log.warn("[cs] llm trace failed: {}", e.getMessage());
        }
    }

    public void recordRetrieval(Long runId, String query, int topK, List<Object> docIds,
                                List<Object> scores, long latencyMs, boolean hit, String backend) {
        if (runId == null || runId == 0L) {
            return;
        }
        try {
            RetrievalTrace trace = new RetrievalTrace();
            trace.setRunId(runId);
            trace.setQuery(truncate(query));
            trace.setTopK(topK);
            trace.setDocIds(docIds);
            trace.setScores(scores);
            trace.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            trace.setHit(hit);
            trace.setBackend(backend);
            retrievalMapper.insert(trace);
        } catch (Exception e) {
            log.warn("[cs] retrieval trace failed: {}", e.getMessage());
        }
    }

    public void recordGuardrail(Long runId, String type, String action, Map<String, Object> detail) {
        if (runId == null || runId == 0L) {
            return;
        }
        try {
            GuardrailEvent event = new GuardrailEvent();
            event.setRunId(runId);
            event.setType(type);
            event.setAction(action);
            event.setDetail(detail);
            guardrailMapper.insert(event);
        } catch (Exception e) {
            log.warn("[cs] guardrail trace failed: {}", e.getMessage());
        }
    }

    @Override
    public void record(ToolCallRecord record) {
        if (record == null || record.runId() == null || record.runId() == 0L) {
            return;
        }
        try {
            ToolCall call = new ToolCall();
            call.setRunId(record.runId());
            call.setStepId(record.stepId());
            call.setToolName(record.toolName());
            call.setArgsJson(maskMap(record.args()));
            call.setResultJson(maskMap(record.result() instanceof Map<?, ?> map
                    ? toStringMap(map) : Map.of("summary", truncate(String.valueOf(record.result())))));
            call.setStatus(record.status());
            call.setLatencyMs((int) Math.min(record.latencyMs(), Integer.MAX_VALUE));
            call.setRetryCount(0);
            call.setIdempotencyKey(record.idempotencyKey());
            call.setErrorCode(record.errorCode());
            toolCallMapper.insert(call);
        } catch (Exception e) {
            log.warn("[cs] tool trace failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), maskValue(v)));
        return result;
    }

    private static Map<String, Object> maskMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, maskValue(v)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object maskValue(Object value) {
        if (value instanceof String text) {
            return truncate(mask(text));
        }
        if (value instanceof Map<?, ?> map) {
            return toStringMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentTraceService::maskValue).toList();
        }
        return value;
    }

    /** 手机号/身份证基础掩码；与 GuardPipeline 的口径保持一致。 */
    static String mask(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2")
                .replaceAll("(\\d{6})\\d{8}(\\d{3}[0-9Xx])", "$1********$2");
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "...[truncated]" : text;
    }
}
