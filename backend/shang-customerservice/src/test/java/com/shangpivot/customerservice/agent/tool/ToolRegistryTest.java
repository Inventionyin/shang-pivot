package com.shangpivot.customerservice.agent.tool;

import com.shangpivot.customerservice.agent.contract.AgentName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void executesAllowedReadToolAndRecordsTrace() {
        AtomicReference<ToolCallRecord> recorded = new AtomicReference<>();
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(ToolRisk.READ, false, 0)),
                pool, Optional.of(recorded::set));

        ToolResult result = registry.execute(context(AgentName.SHOPPING_GUIDE, false),
                "echo", Map.of("text", "hello"));

        assertTrue(result.success());
        assertEquals("hello", result.data());
        assertTrue(recorded.get() != null && "SUCCESS".equals(recorded.get().status()));
    }

    @Test
    void rejectsMissingRequiredArgs() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(ToolRisk.READ, false, 0)),
                pool, Optional.empty());

        ToolResult result = registry.execute(context(AgentName.SHOPPING_GUIDE, false), "echo", Map.of());

        assertFalse(result.success());
        assertEquals("INVALID_ARGS", result.errorCode());
    }

    @Test
    void rejectsAgentOutsideAllowList() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(ToolRisk.READ, false, 0)),
                pool, Optional.empty());

        ToolResult result = registry.execute(context(AgentName.ORDER, false), "echo", Map.of("text", "hi"));

        assertFalse(result.success());
        assertEquals("TOOL_FORBIDDEN", result.errorCode());
    }

    @Test
    void rejectsHighRiskWriteBeforeConfirmation() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(ToolRisk.WRITE_HIGH, false, 0)),
                pool, Optional.empty());

        ToolResult result = registry.execute(context(AgentName.SHOPPING_GUIDE, false), "echo", Map.of("text", "hi"));

        assertFalse(result.success());
        assertEquals("CONFIRMATION_REQUIRED", result.errorCode());
    }

    @Test
    void timesOutSlowTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool(ToolRisk.READ, true, 400)),
                pool, Optional.empty());

        ToolResult result = registry.execute(context(AgentName.SHOPPING_GUIDE, false), "echo",
                Map.of("text", "hi"));

        assertFalse(result.success());
        assertEquals("TIMEOUT", result.errorCode());
    }

    private static ToolContext context(AgentName agent, boolean confirmed) {
        return new ToolContext(1L, 2L, 3L, "trace", agent, confirmed, null);
    }

    /** 极简工具：name=echo，required=text，可选 sleep。 */
    private static final class FakeTool extends AbstractTool {
        private final boolean slow;
        private final long sleepMs;

        private FakeTool(ToolRisk risk, boolean slow, long sleepMs) {
            super("echo", "echo text", risk, Set.of(AgentName.SHOPPING_GUIDE),
                    Duration.ofMillis(120), false,
                    schema(List.of("text"), Map.of("text", property("string", "text"))));
            this.slow = slow;
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            if (slow) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ToolResult.ok(stringArg(args, "text"), "echo");
        }
    }
}
