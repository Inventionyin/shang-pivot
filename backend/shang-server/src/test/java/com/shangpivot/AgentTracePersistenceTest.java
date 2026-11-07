package com.shangpivot;

import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.agent.trace.entity.ToolCall;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.agent.trace.mapper.ToolCallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V25 映射验证：JSONB 字段、时间字段、逻辑删除字段在真实 PostgreSQL 上可写入/读回。
 * 需要可用的 PostgreSQL；CI 由 Flyway 建表，本地可先手工执行 V25。
 */
@SpringBootTest
class AgentTracePersistenceTest {

    @Autowired
    private AgentRunMapper runMapper;
    @Autowired
    private PendingActionMapper pendingActionMapper;
    @Autowired
    private ToolCallMapper toolCallMapper;

    @Test
    void roundTripsJsonbAndTimestamps() {
        AgentRun run = new AgentRun();
        run.setSessionId(1L);
        run.setCustomerId(100L);
        run.setStatus("RUNNING");
        run.setTraceId("test-trace");
        run.setModel("test-model");
        run.setStartedAt(OffsetDateTime.now());
        run.setTotalTokens(0L);
        runMapper.insert(run);
        assertNotNull(run.getId());
        AgentRun loaded = runMapper.selectById(run.getId());
        assertEquals("RUNNING", loaded.getStatus());
        assertNotNull(loaded.getCreatedAt());

        PendingAction action = new PendingAction();
        action.setSessionId(1L);
        action.setCustomerId(100L);
        action.setToolName("cancelOrder");
        action.setArgsJson(Map.of("orderNo", "O20261107001"));
        action.setStatus("PENDING");
        action.setIdempotencyKey("test-idem-" + run.getId());
        action.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        pendingActionMapper.insert(action);
        PendingAction loadedAction = pendingActionMapper.selectById(action.getId());
        assertEquals("O20261107001", String.valueOf(loadedAction.getArgsJson().get("orderNo")));

        // PendingActionService.mark() 用实体 updateById 写 result_json，走 JacksonTypeHandler
        PendingAction patch = new PendingAction();
        patch.setId(action.getId());
        patch.setStatus("EXECUTED");
        patch.setResultJson(Map.of("status", "CLOSED"));
        patch.setUpdatedAt(OffsetDateTime.now());
        pendingActionMapper.updateById(patch);
        PendingAction updated = pendingActionMapper.selectById(action.getId());
        assertEquals("EXECUTED", updated.getStatus());
        assertEquals("CLOSED", String.valueOf(updated.getResultJson().get("status")));

        ToolCall call = new ToolCall();
        call.setRunId(run.getId());
        call.setToolName("getOrder");
        call.setArgsJson(Map.of("orderNo", "O20261107001"));
        call.setResultJson(Map.of("status", "PENDING_PAY"));
        call.setStatus("SUCCESS");
        call.setLatencyMs(12);
        call.setRetryCount(0);
        toolCallMapper.insert(call);
        ToolCall loadedCall = toolCallMapper.selectById(call.getId());
        assertTrue(loadedCall.getResultJson().containsKey("status"));

        // 清理，避免污染本地/CI 库
        toolCallMapper.deleteById(call.getId());
        pendingActionMapper.deleteById(action.getId());
        runMapper.deleteById(run.getId());
    }
}
