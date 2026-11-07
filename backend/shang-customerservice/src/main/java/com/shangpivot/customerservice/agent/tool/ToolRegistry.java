package com.shangpivot.customerservice.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一工具网关：Agent 只能通过这里执行工具。
 * 负责权限（子 Agent 白名单）、参数必填校验、超时、写操作确认闸门、trace 埋点。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
    private final ExecutorService pool;
    private final ToolCallRecorder recorder;

    public ToolRegistry(List<ToolExecutor> tools,
                        @Qualifier("toolExecutorPool") ExecutorService pool,
                        Optional<ToolCallRecorder> recorder) {
        this.pool = pool;
        this.recorder = recorder.orElse(null);
        for (ToolExecutor tool : tools) {
            ToolDefinition def = tool.definition();
            ToolExecutor previous = executors.putIfAbsent(def.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("duplicate tool name: " + def.name());
            }
        }
        log.info("[cs] tool registry initialized: {}", executors.keySet());
    }

    public List<ToolDefinition> definitions() {
        return executors.values().stream().map(ToolExecutor::definition).toList();
    }

    public Optional<ToolDefinition> definition(String name) {
        ToolExecutor executor = executors.get(name);
        return executor == null ? Optional.empty() : Optional.of(executor.definition());
    }

    public ToolResult execute(ToolContext context, String name, Map<String, Object> args) {
        ToolExecutor executor = executors.get(name);
        if (executor == null) {
            return ToolResult.fail("UNKNOWN_TOOL", "未知工具：" + name);
        }
        ToolDefinition definition = executor.definition();
        if (!definition.allows(context.agent())) {
            return ToolResult.fail("TOOL_FORBIDDEN", "当前 Agent 无权调用该工具");
        }
        if (definition.risk() == ToolRisk.WRITE_HIGH && !context.confirmed()) {
            return ToolResult.fail("CONFIRMATION_REQUIRED", "该操作需要用户确认");
        }
        String missing = missingRequired(definition, args);
        if (missing != null) {
            return ToolResult.fail("INVALID_ARGS", "缺少参数：" + missing);
        }

        long started = System.currentTimeMillis();
        Future<ToolResult> future = pool.submit(() -> executor.execute(context, args == null ? Map.of() : args));
        ToolResult result;
        String status;
        try {
            result = future.get(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
            status = result.success() ? "SUCCESS" : "FAILED";
        } catch (TimeoutException e) {
            future.cancel(true);
            result = ToolResult.fail("TIMEOUT", "工具执行超时");
            status = "TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = ToolResult.fail("INTERRUPTED", "工具执行被中断");
            status = "FAILED";
        } catch (ExecutionException e) {
            log.warn("[cs] tool {} failed: {}", name, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            result = ToolResult.fail("TOOL_ERROR", "工具执行失败");
            status = "FAILED";
        }
        long latency = System.currentTimeMillis() - started;
        record(context, definition, args, result, status, latency);
        return result;
    }

    private String missingRequired(ToolDefinition definition, Map<String, Object> args) {
        Object required = definition.inputSchema().get("required");
        if (!(required instanceof List<?> keys)) {
            return null;
        }
        for (Object key : keys) {
            if (key == null) {
                continue;
            }
            String name = String.valueOf(key);
            Object value = args == null ? null : args.get(name);
            if (value == null || (value instanceof String text && text.isBlank())) {
                return name;
            }
        }
        return null;
    }

    private void record(ToolContext context, ToolDefinition definition, Map<String, Object> args,
                        ToolResult result, String status, long latency) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(new ToolCallRecord(context.runId(), null, definition.name(), args,
                    result.data(), status, latency, context.idempotencyKey(), result.errorCode()));
        } catch (Exception e) {
            log.debug("[cs] tool trace record failed: {}", e.getMessage());
        }
    }
}
