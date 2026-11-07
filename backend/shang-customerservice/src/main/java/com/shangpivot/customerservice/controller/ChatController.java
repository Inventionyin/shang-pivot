package com.shangpivot.customerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.LoginUser;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.customerservice.agent.ChatOrchestrator;
import com.shangpivot.customerservice.agent.ChatResponder;
import com.shangpivot.customerservice.agent.graph.AgentGraphOrchestrator;
import com.shangpivot.customerservice.agent.graph.SupervisorAgent;
import com.shangpivot.customerservice.agent.service.PendingActionService;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsAuditLog;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.entity.QuickReply;
import com.shangpivot.customerservice.mapper.QuickReplyMapper;
import com.shangpivot.customerservice.service.ChatSessionService;
import com.shangpivot.customerservice.service.CsAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * C 端智能客服（/api/chat/**，需 C 端登录态；SSE 事件协议见 docs/04-architecture/v3.0/接口设计.md）。
 */
@Tag(name = "C端-智能客服")
@Validated
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatOrchestrator chatOrchestrator;
    private final AgentGraphOrchestrator graphOrchestrator;
    private final PendingActionService pendingActionService;
    private final CsAuditService auditService;
    private final QuickReplyMapper quickReplyMapper;
    private final com.shangpivot.customerservice.service.CsGuardRailService guardRailService;
    private final com.shangpivot.customerservice.agent.GuardPipeline guardPipeline;

    /** legacy=V3.0 两阶段编排；graph=V3.1 LangGraph4j 编排。 */
    @Value("${agent.engine:legacy}")
    private String agentEngine;

    /** 独立命名注入：容器内存在多个 Executor bean（含 Boot 的 applicationTaskExecutor）。 */
    @Resource(name = "csAgentExecutor")
    private Executor csAgentExecutor;

    public record SendMessageRequest(@NotBlank @Size(max = 500, message = "请输入 1-500 字") String content) {
    }

    public record EscalateRequest(Boolean cancel) {
    }

    @Operation(summary = "创建/取进行中会话")
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession(
            @Parameter(description = "true=结束当前进行中会话并新建（开始新会话）")
            @RequestParam(defaultValue = "false") boolean fresh) {
        LoginUser user = SecurityContexts.get();
        ChatSession s = fresh
                ? chatSessionService.startFresh(user.getUserId(), user.getClientType())
                : chatSessionService.getOrCreateActive(user.getUserId(), user.getClientType());
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", s.getId());
        data.put("status", s.getStatus());
        data.put("greeting", "您好，我是尚品汇智能客服小尚~ 想买什么尽管说，我帮您挑！");
        data.put("quickQuestions", recommendedQuestions());
        return Result.ok(data);
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/sessions")
    public Result<?> mySessions(@RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return Result.ok(chatSessionService.listMine(SecurityContexts.requireUserId(), page, Math.min(size, 50)));
    }

    @Operation(summary = "历史消息回放")
    @GetMapping("/sessions/{id}/messages")
    public Result<List<Map<String, Object>>> history(@PathVariable Long id,
                                                     @RequestParam(defaultValue = "0") long beforeId,
                                                     @RequestParam(defaultValue = "30") int limit) {
        ChatSession s = chatSessionService.requireOwned(id, SecurityContexts.requireUserId());
        return Result.ok(chatSessionService.history(s.getId(), beforeId, limit));
    }

    @Operation(summary = "发送消息（SSE 流式应答）")
    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable Long id, @RequestBody SendMessageRequest req) {
        LoginUser user = SecurityContexts.get();
        ChatSession s = chatSessionService.requireOwned(id, user.getUserId());
        // P0 护栏：用户级限流 + 会话级生成互斥（在入库前校验，避免被限流的消息也落库）
        guardRailService.checkChatRateLimit(user.getUserId());
        guardRailService.lockSession(s.getId());
        // P1.5 合规：用户消息入库前做 PII 掩码（手机号/身份证），LLM 与存储均只见掩码
        String content = guardPipeline.maskPii(req.content().trim());

        SseEmitter emitter = new SseEmitter(120_000L);
        // 非进行中-ai：消息照常入库，但不触发 AI 应答
        CsMessage userMsg = chatSessionService.saveUserMessage(s.getId(), content);
        if (!ChatSession.STATUS_AI_ONGOING.equals(s.getStatus())) {
            guardRailService.unlockSession(s.getId());
            sendNoAiReply(emitter, s);
            return emitter;
        }
        auditService.record("message_sent", CsAuditLog.RISK_LOW, s.getId(), user.getUserId(), null);
        csAgentExecutor.execute(() -> {
            // 重新读取会话，避免排队/接入竞态下 AI 误答
            ChatSession fresh = chatSessionService.requireOwned(s.getId(), user.getUserId());
            if (!ChatSession.STATUS_AI_ONGOING.equals(fresh.getStatus())) {
                sendNoAiReply(emitter, fresh);
                emitter.complete();
                return;
            }
            ChatResponder responder = "graph".equalsIgnoreCase(agentEngine) ? graphOrchestrator : chatOrchestrator;
            responder.respond(emitter, fresh, userMsg);
        });
        return emitter;
    }

    @Operation(summary = "转人工 / 撤回转人工")
    @PostMapping("/sessions/{id}/escalate")
    public Result<Map<String, Object>> escalate(@PathVariable Long id, @RequestBody(required = false) EscalateRequest req) {
        Long customerId = SecurityContexts.requireUserId();
        boolean cancel = req != null && Boolean.TRUE.equals(req.cancel());
        ChatSession s = chatSessionService.escalate(id, customerId, cancel);
        auditService.record(cancel ? "escalate_cancel" : "escalate", CsAuditLog.RISK_LOW, id, customerId, null);
        return Result.ok(Map.of("sessionId", s.getId(), "status", s.getStatus()));
    }

    @Operation(summary = "确认执行写操作（V3.1）")
    @PostMapping("/sessions/{id}/actions/{actionId}/confirm")
    public Result<Map<String, Object>> confirmAction(@PathVariable Long id, @PathVariable Long actionId) {
        Long customerId = SecurityContexts.requireUserId();
        chatSessionService.requireOwned(id, customerId);
        PendingAction action = pendingActionService.requireOwned(actionId, customerId);
        if (!id.equals(action.getSessionId())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        String agent = SupervisorAgent.agentForTool(action.getToolName()).name();
        PendingActionService.ConfirmOutcome outcome = pendingActionService.confirm(actionId, customerId,
                SupervisorAgent.agentForTool(action.getToolName()));
        String message = outcome.idempotent()
                ? "该操作已执行过，无需重复确认。"
                : "已执行：" + pendingActionService.summary(action.getToolName(), action.getArgsJson());
        chatSessionService.saveAssistantMessage(id, CsMessage.ROLE_ASSISTANT, message, null, null,
                Map.of("actionId", actionId, "tool", action.getToolName(), "agent", agent), false);
        auditService.record("action_confirmed", CsAuditLog.RISK_MID, id, customerId,
                Map.of("actionId", actionId, "tool", action.getToolName(), "idempotent", outcome.idempotent()));
        Map<String, Object> data = new HashMap<>();
        data.put("actionId", actionId);
        data.put("tool", action.getToolName());
        data.put("message", message);
        data.put("result", outcome.result());
        data.put("idempotent", outcome.idempotent());
        return Result.ok(data);
    }

    @Operation(summary = "取消写操作（V3.1）")
    @PostMapping("/sessions/{id}/actions/{actionId}/cancel")
    public Result<Map<String, Object>> cancelAction(@PathVariable Long id, @PathVariable Long actionId) {
        Long customerId = SecurityContexts.requireUserId();
        chatSessionService.requireOwned(id, customerId);
        PendingAction action = pendingActionService.requireOwned(actionId, customerId);
        if (!id.equals(action.getSessionId())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        PendingAction cancelled = pendingActionService.cancel(actionId, customerId);
        String message = "已取消该操作。";
        chatSessionService.saveAssistantMessage(id, CsMessage.ROLE_ASSISTANT, message, null, null,
                Map.of("actionId", actionId, "tool", action.getToolName()), false);
        auditService.record("action_cancelled", CsAuditLog.RISK_LOW, id, customerId,
                Map.of("actionId", actionId, "tool", action.getToolName()));
        return Result.ok(Map.of("actionId", actionId, "tool", cancelled.getToolName(),
                "status", cancelled.getStatus(), "message", message));
    }

    @Operation(summary = "推荐问（快捷问题）")
    @GetMapping("/quick-questions")
    public Result<List<String>> quickQuestions() {
        return Result.ok(recommendedQuestions());
    }

    private List<String> recommendedQuestions() {
        return quickReplyMapper.selectList(new LambdaQueryWrapper<QuickReply>()
                .eq(QuickReply::getIsRecommended, true)
                .orderByAsc(QuickReply::getSort)
                .last("LIMIT 6"))
                .stream()
                .map(QuickReply::getContent)
                .toList();
    }

    private void sendNoAiReply(SseEmitter emitter, ChatSession s) {
        String tip = switch (s.getStatus()) {
            case ChatSession.STATUS_QUEUED -> "已为您转人工，正在排队，请稍候~";
            case ChatSession.STATUS_HUMAN_ONGOING -> "人工客服正在为您服务，请稍候回复~";
            default -> "会话已结束，点击「新对话」继续咨询~";
        };
        CsMessage saved = chatSessionService.saveAssistantMessage(s.getId(), CsMessage.ROLE_ASSISTANT, tip,
                null, null, null, false);
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("delta", tip), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "messageId", saved.getId(), "sessionStatus", s.getStatus(), "degraded", false),
                    MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开
        }
    }
}
