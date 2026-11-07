package com.shangpivot.customerservice.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.ToolRegistry;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingActionServiceTest {

    static {
        // 纯单测没有 MyBatis-Plus 启动流程，先注册实体的 lambda 列缓存
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), PendingAction.class);
    }

    private final PendingActionMapper mapper = Mockito.mock(PendingActionMapper.class);
    private final ToolRegistry toolRegistry = Mockito.mock(ToolRegistry.class);
    private final ChatSessionService chatSessionService = Mockito.mock(ChatSessionService.class);
    private final PendingActionService service = new PendingActionService(
            mapper, toolRegistry, chatSessionService, new ObjectMapper());

    private PendingAction action;

    @BeforeEach
    void setUp() {
        action = new PendingAction();
        action.setId(7L);
        action.setSessionId(1L);
        action.setCustomerId(100L);
        action.setToolName("cancelOrder");
        action.setArgsJson(Map.of("orderNo", "O20261107001"));
        action.setStatus(PendingActionService.PENDING);
        action.setIdempotencyKey("idem-1");
        action.setExpiresAt(OffsetDateTime.now().plusMinutes(10));

        when(mapper.selectById(7L)).thenReturn(action);
        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setCustomerId(100L);
        when(chatSessionService.requireOwned(1L, 100L)).thenReturn(session);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(toolRegistry.execute(any(), eq("cancelOrder"), any()))
                .thenReturn(ToolResult.ok(Map.of("status", "CLOSED"), "已取消"));
    }

    @Test
    void confirmExecutesExactlyOnce() {
        PendingActionService.ConfirmOutcome first = service.confirm(7L, 100L, AgentName.ORDER);
        assertTrue(first.result().containsKey("status"));

        PendingActionService.ConfirmOutcome second = service.confirm(7L, 100L, AgentName.ORDER);
        assertTrue(second.idempotent());
        verify(toolRegistry, times(1)).execute(any(), eq("cancelOrder"), any());
    }

    @Test
    void cancelPreventsExecution() {
        PendingAction cancelled = service.cancel(7L, 100L);

        assertEquals(PendingActionService.CANCELLED, cancelled.getStatus());
        verify(toolRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void expiredActionIsRejected() {
        action.setExpiresAt(OffsetDateTime.now().minusMinutes(1));

        assertThrows(BizException.class, () -> service.confirm(7L, 100L, AgentName.ORDER));
        verify(toolRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void confirmedWithPreviousErrorCanRetry() {
        action.setStatus(PendingActionService.CONFIRMED);
        action.setResultJson(Map.of("error", "上次超时"));

        PendingActionService.ConfirmOutcome outcome = service.confirm(7L, 100L, AgentName.ORDER);

        assertTrue(outcome.result().containsKey("status"));
        assertEquals(PendingActionService.EXECUTED, action.getStatus());
        verify(toolRegistry, times(1)).execute(any(), eq("cancelOrder"), any());
    }

    @Test
    void confirmedInProgressRejectsConcurrentConfirm() {
        action.setStatus(PendingActionService.CONFIRMED);
        action.setResultJson(null);

        assertThrows(BizException.class, () -> service.confirm(7L, 100L, AgentName.ORDER));
        verify(toolRegistry, never()).execute(any(), any(), any());
    }

    @Test
    void summaryIsHumanReadable() {
        assertEquals("取消订单 O20261107001",
                service.summary("cancelOrder", Map.of("orderNo", "O20261107001")));
        assertEquals("为订单 O20261107001 申请售后",
                service.summary("createAfterSale", Map.of("orderNo", "O20261107001")));
    }
}
