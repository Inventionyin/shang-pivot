package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.entity.MessageFeedback;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.mapper.MessageFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 单条 AI 回答的点踩/点赞反馈：同一用户对同一条消息只保留一条记录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageFeedbackService {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    private static final Set<String> REASONS = Set.of(
            "答非所问", "商品不对", "没有执行", "信息不准确", "态度不好", "其他");

    private final MessageFeedbackMapper feedbackMapper;
    private final CsMessageMapper messageMapper;
    private final ChatSessionService chatSessionService;
    private final AgentRunMapper agentRunMapper;

    @Transactional
    public MessageFeedback submit(Long customerId, Long messageId, String rating, String reason, String comment) {
        String normalized = rating == null ? "" : rating.trim().toUpperCase();
        if (!UP.equals(normalized) && !DOWN.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "反馈类型只支持 UP/DOWN");
        }
        String safeReason = reason == null ? "" : reason.trim();
        if (!safeReason.isEmpty() && !REASONS.contains(safeReason)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知的反馈原因");
        }
        String safeComment = comment == null ? "" : comment.trim();
        if (safeComment.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "反馈补充最多 500 字");
        }

        CsMessage message = messageMapper.selectById(messageId);
        if (message == null || !CsMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BizException(ErrorCode.NOT_FOUND, "回答不存在");
        }
        ChatSession session = chatSessionService.requireOwned(message.getSessionId(), customerId);

        MessageFeedback existing = feedbackMapper.selectOne(new LambdaQueryWrapper<MessageFeedback>()
                .eq(MessageFeedback::getMessageId, messageId)
                .eq(MessageFeedback::getCustomerId, customerId)
                .last("LIMIT 1"));
        MessageFeedback feedback = existing == null ? new MessageFeedback() : existing;
        feedback.setMessageId(messageId);
        feedback.setSessionId(session.getId());
        feedback.setCustomerId(customerId);
        feedback.setRating(normalized);
        feedback.setReason(safeReason.isBlank() ? null : safeReason);
        feedback.setComment(safeComment.isBlank() ? null : safeComment);
        feedback.setRunId(runIdOf(messageId));
        if (existing == null) {
            feedbackMapper.insert(feedback);
        } else {
            feedbackMapper.updateById(feedback);
        }
        log.info("[cs] feedback {} message={} customer={} reason={}", normalized, messageId,
                customerId, safeReason);
        return feedback;
    }

    private Long runIdOf(Long messageId) {
        try {
            AgentRun run = agentRunMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                    .eq(AgentRun::getAssistantMessageId, messageId)
                    .orderByDesc(AgentRun::getId)
                    .last("LIMIT 1"));
            return run == null ? null : run.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
