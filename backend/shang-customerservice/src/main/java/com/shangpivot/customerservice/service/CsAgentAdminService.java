package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsAuditLog;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 坐席侧会话管理（接入/回复/转接/结束/退回 AI）。 */
@Service
@RequiredArgsConstructor
public class CsAgentAdminService {

    private final ChatSessionMapper sessionMapper;
    private final CsMessageMapper messageMapper;
    private final SeatAdminService seatAdminService;
    private final CsAuditService auditService;

    public PageResult<Map<String, Object>> page(String status, int page, int size) {
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ChatSession>()
                .eq(StringUtils.hasText(status), ChatSession::getStatus, status)
                .orderByDesc(ChatSession::getId));
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toVo).toList());
    }

    public Map<String, Object> detail(Long sessionId) {
        ChatSession s = require(sessionId);
        Map<String, Object> m = toVo(s);
        List<Map<String, Object>> messages = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                        .eq(CsMessage::getSessionId, sessionId).orderByAsc(CsMessage::getId))
                .stream().map(msg -> {
                    Map<String, Object> mm = new HashMap<>();
                    mm.put("id", msg.getId());
                    mm.put("role", msg.getRole());
                    mm.put("contentType", msg.getContentType());
                    mm.put("content", msg.getContent());
                    mm.put("metadata", msg.getMetadata());
                    mm.put("degraded", Boolean.TRUE.equals(msg.getDegraded()));
                    mm.put("createdAt", msg.getCreatedAt());
                    return mm;
                }).toList();
        m.put("messages", messages);
        return m;
    }

    /** 接入：乐观锁抢占排队会话。 */
    public ChatSession claim(Long sessionId, Long adminUserId) {
        var seat = seatAdminService.requireByAdminUser(adminUserId);
        seatAdminService.checkCanClaim(seat);
        ChatSession s = require(sessionId);
        if (!seatAdminService.claimQueued(s, seat)) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "会话已被其他坐席接入");
        }
        auditService.record("session_claimed", CsAuditLog.RISK_LOW, sessionId, s.getCustomerId(),
                Map.of("seatId", seat.getId()));
        return require(sessionId);
    }

    /** 坐席回复（同步落库，会员侧轮询历史/会话态可见）。 */
    public CsMessage reply(Long sessionId, Long adminUserId, String content) {
        var seat = seatAdminService.requireByAdminUser(adminUserId);
        ChatSession s = require(sessionId);
        if (!ChatSession.STATUS_HUMAN_ONGOING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "会话不在人工服务状态");
        }
        CsMessage msg = new CsMessage();
        msg.setSessionId(sessionId);
        msg.setRole(CsMessage.ROLE_AGENT);
        msg.setContentType(CsMessage.TYPE_TEXT);
        msg.setContent(content);
        msg.setMetadata(Map.of("seatId", seat.getId(), "seatName", seat.getNickname()));
        messageMapper.insert(msg);
        return msg;
    }

    public ChatSession transfer(Long sessionId, Long adminUserId, Long targetSeatId) {
        requireBySeat(adminUserId);
        ChatSession s = require(sessionId);
        if (!ChatSession.STATUS_HUMAN_ONGOING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "会话不在人工服务状态");
        }
        seatAdminService.transferTo(s, targetSeatId);
        auditService.record("session_transferred", CsAuditLog.RISK_LOW, sessionId, s.getCustomerId(),
                Map.of("targetSeatId", targetSeatId));
        return require(sessionId);
    }

    public ChatSession end(Long sessionId, String summary) {
        ChatSession s = require(sessionId);
        if (StringUtils.hasText(summary)) {
            s.setSummary(summary.length() > 255 ? summary.substring(0, 255) : summary);
        }
        ChatSession ended = endSession(s);
        auditService.record("session_ended", CsAuditLog.RISK_LOW, sessionId, s.getCustomerId(), null);
        return ended;
    }

    public ChatSession backToAi(Long sessionId) {
        ChatSession s = require(sessionId);
        if (!ChatSession.STATUS_HUMAN_ONGOING.equals(s.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "会话不在人工服务状态");
        }
        s.setStatus(ChatSession.STATUS_AI_ONGOING);
        s.setSeatId(null);
        sessionMapper.updateById(s);
        auditService.record("session_back_to_ai", CsAuditLog.RISK_LOW, sessionId, s.getCustomerId(), null);
        return s;
    }

    private ChatSession endSession(ChatSession s) {
        if (ChatSession.STATUS_ENDED.equals(s.getStatus())) {
            return s;
        }
        s.setStatus(ChatSession.STATUS_ENDED);
        s.setEndedAt(java.time.OffsetDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    private ChatSession require(Long sessionId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || Boolean.TRUE.equals(s.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    /** 坐席身份校验（转接/结束时要求操作者是坐席）。 */
    private void requireBySeat(Long adminUserId) {
        seatAdminService.requireByAdminUser(adminUserId);
    }

    private Map<String, Object> toVo(ChatSession s) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", s.getId());
        m.put("customerId", s.getCustomerId());
        m.put("clientType", s.getClientType());
        m.put("status", s.getStatus());
        m.put("seatId", s.getSeatId());
        m.put("summary", s.getSummary());
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        return m;
    }
}
