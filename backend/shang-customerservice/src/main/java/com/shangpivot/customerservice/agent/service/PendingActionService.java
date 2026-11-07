package com.shangpivot.customerservice.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRegistry;
import com.shangpivot.customerservice.agent.trace.entity.PendingAction;
import com.shangpivot.customerservice.agent.trace.mapper.PendingActionMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写操作待确认动作：模型只生成计划，用户确认后才由 ToolRegistry 执行。
 * 幂等键唯一 + 状态 CAS，保证同一动作最多执行一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingActionService {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXECUTED = "EXECUTED";
    public static final String EXPIRED = "EXPIRED";

    private static final Duration TTL = Duration.ofMinutes(10);

    private final PendingActionMapper mapper;
    private final ToolRegistry toolRegistry;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    /** 生成或复用待确认动作（同会话同工具同参数只保留一条 PENDING/CONFIRMED）。 */
    public PendingAction createOrReuse(Long sessionId, Long customerId, Long runId, AgentName agent,
                                       String toolName, Map<String, Object> args) {
        String key = idempotencyKey(sessionId, toolName, args);
        PendingAction existing = mapper.selectOne(new LambdaQueryWrapper<PendingAction>()
                .eq(PendingAction::getIdempotencyKey, key)
                .in(PendingAction::getStatus, PENDING, CONFIRMED)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        PendingAction action = new PendingAction();
        action.setSessionId(sessionId);
        action.setCustomerId(customerId);
        action.setRunId(runId);
        action.setToolName(toolName);
        action.setArgsJson(args);
        action.setStatus(PENDING);
        action.setIdempotencyKey(key);
        action.setExpiresAt(OffsetDateTime.now().plus(TTL));
        try {
            mapper.insert(action);
            return action;
        } catch (Exception e) {
            // 并发插入撞唯一键：回读已有 PENDING
            PendingAction raced = mapper.selectOne(new LambdaQueryWrapper<PendingAction>()
                    .eq(PendingAction::getIdempotencyKey, key)
                    .in(PendingAction::getStatus, PENDING, CONFIRMED)
                    .last("LIMIT 1"));
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    public PendingAction requireOwned(Long actionId, Long customerId) {
        PendingAction action = mapper.selectById(actionId);
        if (action == null || Boolean.TRUE.equals(action.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND, "操作不存在或已过期");
        }
        ChatSession session = chatSessionService.requireOwned(action.getSessionId(), customerId);
        if (!session.getCustomerId().equals(customerId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return action;
    }

    /** 确认并执行；重复确认返回已执行结果，不重复写。 */
    public ConfirmOutcome confirm(Long actionId, Long customerId, AgentName agent) {
        PendingAction action = requireOwned(actionId, customerId);
        if (EXECUTED.equals(action.getStatus())) {
            return new ConfirmOutcome(action, cachedResult(action), true);
        }
        if (CANCELLED.equals(action.getStatus()) || EXPIRED.equals(action.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该操作已取消或已过期");
        }
        if (action.getExpiresAt() != null && action.getExpiresAt().isBefore(OffsetDateTime.now())) {
            mark(action.getId(), PENDING, EXPIRED, null);
            throw new BizException(ErrorCode.CONFLICT, "该操作已过期，请重新发起");
        }
        // CONFIRMED 只有“上次失败（result_json.error）”才允许重试；否则视为另一个请求正在执行
        if (CONFIRMED.equals(action.getStatus())) {
            Map<String, Object> previous = action.getResultJson();
            boolean failedBefore = previous != null && previous.containsKey("error");
            if (!failedBefore) {
                throw new BizException(ErrorCode.CONFLICT, "该操作正在处理中，请稍候");
            }
        }
        // PENDING 首次确认：CAS PENDING -> CONFIRMED，只有第一个请求能赢。
        // CONFIRMED 是“执行失败待重试”状态，允许同一动作在 TTL 内重试。
        if (PENDING.equals(action.getStatus())) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<PendingAction>()
                    .eq(PendingAction::getId, action.getId())
                    .eq(PendingAction::getStatus, PENDING)
                    .set(PendingAction::getStatus, CONFIRMED)
                    .set(PendingAction::getUpdatedAt, OffsetDateTime.now()));
            if (updated == 0) {
                PendingAction latest = mapper.selectById(action.getId());
                if (latest != null && EXECUTED.equals(latest.getStatus())) {
                    return new ConfirmOutcome(latest, cachedResult(latest), true);
                }
                if (latest == null || !CONFIRMED.equals(latest.getStatus())) {
                    throw new BizException(ErrorCode.CONFLICT, "该操作正在处理中或状态已变化");
                }
                // CONFIRMED 且已有 error → 上次执行失败，允许重试；否则视为另一个请求正在执行
                Map<String, Object> latestResult = latest.getResultJson();
                boolean failedBefore = latestResult != null && latestResult.containsKey("error");
                if (!failedBefore) {
                    throw new BizException(ErrorCode.CONFLICT, "该操作正在处理中，请稍候");
                }
            }
            action.setStatus(CONFIRMED);
        }

        ToolContext context = new ToolContext(customerId, action.getSessionId(), action.getRunId(),
                null, agent == null ? AgentName.ORDER : agent, true, action.getIdempotencyKey());
        ToolResult result;
        try {
            result = toolRegistry.execute(context, action.getToolName(), action.getArgsJson());
        } catch (Exception e) {
            Map<String, Object> error = Map.of("error", e.getMessage() == null ? "执行异常" : e.getMessage());
            mark(action.getId(), CONFIRMED, CONFIRMED, error);
            throw new BizException(ErrorCode.BAD_REQUEST, "操作执行失败，可在有效期内重试");
        }
        Map<String, Object> resultMap = toMap(result);
        if (result.success()) {
            mark(action.getId(), CONFIRMED, EXECUTED, resultMap);
            action.setStatus(EXECUTED);
            action.setResultJson(resultMap);
            return new ConfirmOutcome(action, resultMap, false);
        }
        // 失败保持 CONFIRMED（ADR-0008），把错误写进 result_json，TTL 内可重试
        Map<String, Object> error = new java.util.LinkedHashMap<>(resultMap);
        error.put("error", result.errorMessage() == null ? "操作执行失败" : result.errorMessage());
        error.put("errorCode", result.errorCode());
        mark(action.getId(), CONFIRMED, CONFIRMED, error);
        throw new BizException(ErrorCode.BAD_REQUEST,
                result.errorMessage() == null ? "操作执行失败，可在有效期内重试" : result.errorMessage());
    }

    public PendingAction cancel(Long actionId, Long customerId) {
        PendingAction action = requireOwned(actionId, customerId);
        if (EXECUTED.equals(action.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该操作已执行，无法取消");
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<PendingAction>()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getStatus, PENDING)
                .set(PendingAction::getStatus, CANCELLED)
                .set(PendingAction::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该操作状态已变化，无法取消");
        }
        action.setStatus(CANCELLED);
        return action;
    }

    public String summary(String toolName, Map<String, Object> args) {
        String orderNo = args == null ? null : String.valueOf(args.getOrDefault("orderNo", ""));
        return switch (toolName) {
            case "cancelOrder" -> "取消订单 " + orderNo;
            case "confirmReceive" -> "确认收货 " + orderNo;
            case "createAfterSale" -> "为订单 " + orderNo + " 申请售后";
            case "addToCart" -> "加入购物车（SKU " + (args == null ? "" : args.get("skuId")) + "）";
            case "createTicket" -> "创建客服工单";
            default -> toolName;
        };
    }

    /**
     * 状态 CAS 只更新标量列；result_json 用实体 updateById 走 JacksonTypeHandler。
     * 不能把 Map 直接放进 LambdaUpdateWrapper.set：MyBatis 会按 Object 绑定，PG 驱动会当 hstore 处理。
     */
    private void mark(Long id, String from, String to, Map<String, Object> result) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<PendingAction>()
                .eq(PendingAction::getId, id)
                .eq(PendingAction::getStatus, from)
                .set(PendingAction::getStatus, to)
                .set(PendingAction::getUpdatedAt, OffsetDateTime.now()));
        if (updated > 0 && result != null) {
            PendingAction patch = new PendingAction();
            patch.setId(id);
            patch.setStatus(to);
            patch.setResultJson(result);
            patch.setUpdatedAt(OffsetDateTime.now());
            mapper.updateById(patch);
        }
    }

    private Map<String, Object> cachedResult(PendingAction action) {
        return action.getResultJson() == null ? Map.of() : action.getResultJson();
    }

    private Map<String, Object> toMap(ToolResult result) {
        if (result.data() instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        try {
            return objectMapper.convertValue(result.data(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("message", result.summary() == null ? "" : result.summary());
        }
    }

    static String idempotencyKey(Long sessionId, String toolName, Map<String, Object> args) {
        try {
            String raw = sessionId + "|" + toolName + "|" + args;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 64);
        } catch (Exception e) {
            return sessionId + ":" + toolName + ":" + Math.abs(String.valueOf(args).hashCode());
        }
    }

    public record ConfirmOutcome(PendingAction action, Map<String, Object> result, boolean idempotent) {
    }
}
