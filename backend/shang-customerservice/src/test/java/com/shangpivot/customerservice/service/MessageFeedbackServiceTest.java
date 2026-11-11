package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.entity.MessageFeedback;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import com.shangpivot.customerservice.mapper.MessageFeedbackMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageFeedbackServiceTest {

    private final MessageFeedbackMapper feedbackMapper = Mockito.mock(MessageFeedbackMapper.class);
    private final CsMessageMapper messageMapper = Mockito.mock(CsMessageMapper.class);
    private final ChatSessionService chatSessionService = Mockito.mock(ChatSessionService.class);
    private final AgentRunMapper agentRunMapper = Mockito.mock(AgentRunMapper.class);
    private final MessageFeedbackService service = new MessageFeedbackService(
            feedbackMapper, messageMapper, chatSessionService, agentRunMapper);

    @Test
    void 首次点赞写入反馈() {
        when(messageMapper.selectById(11L)).thenReturn(assistantMessage(11L, 7L));
        when(chatSessionService.requireOwned(7L, 3L)).thenReturn(session(7L, 3L));
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        when(agentRunMapper.selectOne(any())).thenReturn(null);

        MessageFeedback feedback = service.submit(3L, 11L, "up", null, null);

        assertThat(feedback.getRating()).isEqualTo("UP");
        assertThat(feedback.getSessionId()).isEqualTo(7L);
        verify(feedbackMapper).insert(any(MessageFeedback.class));
        verify(feedbackMapper, never()).updateById(any(MessageFeedback.class));
    }

    @Test
    void 重复点踩覆盖原反馈() {
        when(messageMapper.selectById(11L)).thenReturn(assistantMessage(11L, 7L));
        when(chatSessionService.requireOwned(7L, 3L)).thenReturn(session(7L, 3L));
        MessageFeedback existing = new MessageFeedback();
        existing.setId(99L);
        existing.setMessageId(11L);
        existing.setCustomerId(3L);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);
        when(agentRunMapper.selectOne(any())).thenReturn(null);

        MessageFeedback feedback = service.submit(3L, 11L, "DOWN", "答非所问", "把冰箱答成了手机");

        assertThat(feedback.getRating()).isEqualTo("DOWN");
        assertThat(feedback.getReason()).isEqualTo("答非所问");
        verify(feedbackMapper).updateById(existing);
        verify(feedbackMapper, never()).insert(any(MessageFeedback.class));
    }

    @Test
    void 非法反馈类型被拒绝() {
        assertThatThrownBy(() -> service.submit(3L, 11L, "MAYBE", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UP/DOWN");
    }

    @Test
    void 非助手消息不能反馈() {
        CsMessage user = assistantMessage(11L, 7L);
        user.setRole(CsMessage.ROLE_USER);
        when(messageMapper.selectById(11L)).thenReturn(user);

        assertThatThrownBy(() -> service.submit(3L, 11L, "DOWN", "答非所问", null))
                .isInstanceOf(BizException.class);
    }

    private static CsMessage assistantMessage(Long id, Long sessionId) {
        CsMessage message = new CsMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setRole(CsMessage.ROLE_ASSISTANT);
        return message;
    }

    private static ChatSession session(Long id, Long customerId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setCustomerId(customerId);
        return session;
    }
}
