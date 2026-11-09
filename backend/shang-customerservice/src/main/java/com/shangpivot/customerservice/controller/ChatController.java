package com.shangpivot.customerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
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
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
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
@Slf4j
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatOrchestrator chatOrchestrator;
    private final AgentGraphOrchestrator graphOrchestrator;
    private final PendingActionService pendingActionService;
    private final CsAuditService auditService;
    private final QuickReplyMapper quickReplyMapper;
    private final com.shangpivot.customerservice.service.CsGuardRailService guardRailService;
    private final com.shangpivot.customerservice.agent.GuardPipeline guardPipeline;
    private final ProductQueryApi productQueryApi;

    /** legacy=V3.0 两阶段编排；graph=V3.1 LangGraph4j 编排。 */
    @Value("${agent.engine:legacy}")
    private String agentEngine;

    /** 可接收 X-Agent-Test 的测试账号手机号；为空时兼容开发环境放开。 */
    @Value("${agent.test.allowed-phones:}")
    private String allowedTestPhones;

    /** 独立命名注入：容器内存在多个 Executor bean（含 Boot 的 applicationTaskExecutor）。 */
    @Resource(name = "csAgentExecutor")
    private Executor csAgentExecutor;

    /**
     * 文本消息或商品卡按钮动作。
     * 商品卡按钮只传 spuId，后端按当前商品名生成“把「商品名」加入购物车”，避免前端自由文本歧义。
     */
    public record SendMessageRequest(@Size(max = 500, message = "请输入 1-500 字") String content, Long spuId) {
    }

    public record EscalateRequest(Boolean cancel) {
    }

    @Operation(summary = "创建/取进行中会话")
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession(
            @Parameter(description = "true=结束当前进行中会话并新建（开始新会话）")
            @RequestParam(defaultValue = "false") boolean fresh,
            @Parameter(description = "平台测试标记：ai/human；生产请求不传")
            @RequestHeader(value = "X-Agent-Test", required = false) String testHeader,
            @RequestHeader(value = "X-Agent-Test-Run-Id", required = false) String testRunId,
            @RequestHeader(value = "X-Chat-Client-Key", required = false) String clientKeyHeader) {
        LoginUser user = SecurityContexts.get();
        String clientKey = normalizeClientKey(clientKeyHeader);
        boolean testRequested = testHeader != null && !testHeader.isBlank() && !"0".equals(testHeader);
        boolean isTest = testRequested && isAllowedTestUser(user.getUsername());
        if (testRequested && !isTest) {
            log.warn("[cs] ignore X-Agent-Test for non-allowlisted user {}", user.getUserId());
        }
        String testSource = isTest ? ("human".equalsIgnoreCase(testHeader) ? "human" : "ai") : "";
        String lockToken = guardRailService.lockSessionCreate(user.getUserId(), clientKey);
        try {
            ChatSession s = fresh
                    ? chatSessionService.startFresh(user.getUserId(), user.getClientType(), clientKey,
                            isTest, testSource, testRunId)
                    : chatSessionService.getOrCreateActive(user.getUserId(), user.getClientType(), clientKey,
                            isTest, testSource, testRunId);
            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", s.getId());
            data.put("status", s.getStatus());
            data.put("isTest", Boolean.TRUE.equals(s.getIsTest()));
            data.put("testSource", s.getTestSource());
            data.put("resumed", Boolean.TRUE.equals(s.getResumed()));
            data.put("previousSessionId", s.getPreviousSessionId());
            data.put("greeting", "您好，我是尚品汇智能客服小尚~ 想买什么尽管说，我帮您挑！");
            data.put("quickQuestions", recommendedQuestions());
            return Result.ok(data);
        } finally {
            guardRailService.unlockSessionCreate(user.getUserId(), clientKey, lockToken);
        }
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

    @Operation(summary = "会话状态与待确认动作")
    @GetMapping("/sessions/{id}")
    public Result<Map<String, Object>> sessionState(@PathVariable Long id) {
        ChatSession s = chatSessionService.requireOwned(id, SecurityContexts.requireUserId());
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", s.getId());
        data.put("status", s.getStatus());
        data.put("isTest", Boolean.TRUE.equals(s.getIsTest()));
        data.put("testSource", s.getTestSource());
        data.put("lastMessageAt", s.getLastMessageAt());
        PendingAction pending = pendingActionService.latestPending(s.getId());
        if (pending != null && (pending.getExpiresAt() == null
                || pending.getExpiresAt().isAfter(OffsetDateTime.now()))) {
            Map<String, Object> action = new HashMap<>();
            action.put("actionId", pending.getId());
            action.put("tool", pending.getToolName());
            action.put("summary", pendingActionService.summary(pending.getToolName(), pending.getArgsJson()));
            action.put("args", pending.getArgsJson());
            action.put("expiresAt", pending.getExpiresAt());
            data.put("pendingAction", action);
        }
        return Result.ok(data);
    }

    /** 商品卡按钮只传 spuId；文本消息走 content。两者互斥，spuId 优先。 */
    private String resolveSendContent(SendMessageRequest req) {
        if (req.spuId() != null) {
            List<ProductCardDTO> cards = productQueryApi.getBySpuIds(List.of(req.spuId()));
            if (cards.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND, "商品已下架，请刷新后重试");
            }
            return "把「" + cards.get(0).name() + "」加入购物车";
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入消息");
        }
        if (content.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入 1-500 字");
        }
        return content;
    }

    @Operation(summary = "发送消息（SSE 流式应答）")
    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable Long id, @RequestBody SendMessageRequest req) {
        LoginUser user = SecurityContexts.get();
        ChatSession s = chatSessionService.requireOwned(id, user.getUserId());
        // 已结束（含 30 分钟空闲自动结束）不再把消息静默写进旧会话，避免用户以为消息已发送。
        if (ChatSession.STATUS_ENDED.equals(s.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "会话已结束，请点击「新对话」继续咨询");
        }
        // P0 护栏：用户级限流 + 会话级生成互斥（在入库前校验，避免被限流的消息也落库）
        guardRailService.checkChatRateLimit(user.getUserId());
        guardRailService.lockSession(s.getId());
        // P1.5 合规：用户消息入库前做 PII 掩码（手机号/身份证），LLM 与存储均只见掩码
        String content = guardPipeline.maskPii(resolveSendContent(req));

        SseEmitter emitter = new SseEmitter(120_000L);
        // 非进行中-ai：消息照常入库，但不触发 AI 应答
        CsMessage userMsg = chatSessionService.saveUserMessage(s.getId(), content);
        if (!ChatSession.STATUS_AI_ONGOING.equals(s.getStatus())) {
            guardRailService.unlockSession(s.getId());
            sendNoAiReply(emitter, s);
            return emitter;
        }
        // 文字确认：存在待确认动作时，用户回复“确认/好的”直接执行，不再走意图模型。
        PendingAction pending = pendingActionService.latestPending(s.getId());
        if (pending != null && isConfirmText(content)) {
            guardRailService.unlockSession(s.getId());
            confirmByText(emitter, s, pending, user.getUserId());
            return emitter;
        }
        if (pending != null && isCancelText(content)) {
            guardRailService.unlockSession(s.getId());
            cancelByText(emitter, s, pending, user.getUserId());
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

    /** 测试标记只允许测试账号池使用；未配置时兼容开发环境。 */
    private boolean isAllowedTestUser(String username) {
        if (allowedTestPhones == null || allowedTestPhones.isBlank()) {
            return true;
        }
        if (username == null) {
            return false;
        }
        for (String phone : allowedTestPhones.split(",")) {
            if (username.equals(phone.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeClientKey(String clientKey) {
        if (clientKey == null) {
            return "";
        }
        String key = clientKey.trim();
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    private boolean isConfirmText(String text) {
        return text != null && text.trim().matches("^(确认|确认执行|确定|好的|可以|执行|是|是的|ok|OK)$");
    }

    private boolean isCancelText(String text) {
        return text != null && text.trim().matches("^(取消|取消操作|不用了|算了|不要了|别执行)$");
    }

    /** 文字确认：复用与确认按钮相同的 PendingAction 执行路径。 */
    private void confirmByText(SseEmitter emitter, ChatSession session, PendingAction action, Long customerId) {
        String message;
        try {
            PendingActionService.ConfirmOutcome outcome = pendingActionService.confirm(
                    action.getId(), customerId, SupervisorAgent.agentForTool(action.getToolName()));
            message = outcome.idempotent()
                    ? "该操作已执行过，无需重复确认。"
                    : "已执行：" + pendingActionService.summary(action.getToolName(), action.getArgsJson());
            auditService.record("action_confirmed", CsAuditLog.RISK_MID, session.getId(), customerId,
                    Map.of("actionId", action.getId(), "tool", action.getToolName(), "via", "text"));
        } catch (Exception e) {
            message = e.getMessage() == null ? "操作执行失败，请稍后重试。" : e.getMessage();
        }
        CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                message, null, null, Map.of("actionId", action.getId(), "tool", action.getToolName()), false);
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("delta", message), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "messageId", saved.getId(), "sessionStatus", session.getStatus(), "degraded", false),
                    MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端断开
        }
    }

    private void cancelByText(SseEmitter emitter, ChatSession session, PendingAction action, Long customerId) {
        String message;
        try {
            pendingActionService.cancel(action.getId(), customerId);
            message = "已取消该操作。";
            auditService.record("action_cancelled", CsAuditLog.RISK_LOW, session.getId(), customerId,
                    Map.of("actionId", action.getId(), "tool", action.getToolName(), "via", "text"));
        } catch (Exception e) {
            message = e.getMessage() == null ? "取消失败，请稍后重试。" : e.getMessage();
        }
        CsMessage saved = chatSessionService.saveAssistantMessage(session.getId(), CsMessage.ROLE_ASSISTANT,
                message, null, null, Map.of("actionId", action.getId(), "tool", action.getToolName()), false);
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("delta", message), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "messageId", saved.getId(), "sessionStatus", session.getStatus(), "degraded", false),
                    MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端断开
        }
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
