package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.entity.CsAuditLog;
import com.shangpivot.customerservice.entity.CsTicket;
import com.shangpivot.customerservice.mapper.CsTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 客服工单：CRUD + 状态流转（OPEN→PROCESSING→RESOLVED，可退回）。 */
@Service
@RequiredArgsConstructor
public class TicketService {

    private final CsTicketMapper ticketMapper;
    private final CsAuditService auditService;

    public PageResult<Map<String, Object>> page(String status, String type, int page, int size) {
        Page<CsTicket> p = ticketMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<CsTicket>()
                .eq(StringUtils.hasText(status), CsTicket::getStatus, status)
                .eq(StringUtils.hasText(type), CsTicket::getType, type)
                .orderByDesc(CsTicket::getId));
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toVo).toList());
    }

    public CsTicket create(Long sessionId, Long customerId, String type, String title, String remark, Long seatId) {
        validate(type, title, remark);
        CsTicket t = new CsTicket();
        t.setSessionId(sessionId);
        t.setCustomerId(customerId);
        t.setType(type);
        t.setTitle(title);
        t.setRemark(remark);
        t.setSeatId(seatId);
        t.setStatus(CsTicket.STATUS_OPEN);
        t.setSlaDueAt(OffsetDateTime.now().plusHours(24));
        ticketMapper.insert(t);
        auditService.record("ticket_created", CsAuditLog.RISK_LOW, sessionId, customerId, Map.of("ticketId", t.getId()));
        return t;
    }

    public CsTicket update(Long id, String type, String title, String remark) {
        CsTicket t = require(id);
        validate(type, title, remark);
        t.setType(type);
        t.setTitle(title);
        t.setRemark(remark);
        ticketMapper.updateById(t);
        return t;
    }

    /** 状态流转：OPEN→PROCESSING→RESOLVED；RESOLVED→PROCESSING 退回。 */
    public CsTicket transition(Long id, String target, Long adminUserId) {
        CsTicket t = require(id);
        String cur = t.getStatus();
        boolean allowed = ("PROCESSING".equals(target) && CsTicket.STATUS_OPEN.equals(cur))
                || ("RESOLVED".equals(target) && CsTicket.STATUS_PROCESSING.equals(cur))
                || ("PROCESSING".equals(target) && CsTicket.STATUS_RESOLVED.equals(cur));
        if (!allowed) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "工单状态不允许该流转");
        }
        t.setStatus(target);
        if (CsTicket.STATUS_RESOLVED.equals(target)) {
            t.setResolvedAt(OffsetDateTime.now());
        } else {
            t.setResolvedAt(null);
        }
        ticketMapper.updateById(t);
        auditService.record("ticket_transition", CsAuditLog.RISK_LOW, t.getSessionId(), t.getCustomerId(),
                Map.of("ticketId", id, "from", cur, "to", target, "adminUserId", adminUserId));
        return t;
    }

    public void delete(Long id) {
        require(id);
        ticketMapper.deleteById(id);
    }

    private CsTicket require(Long id) {
        CsTicket t = ticketMapper.selectById(id);
        if (t == null || Boolean.TRUE.equals(t.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private static void validate(String type, String title, String remark) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "标题必填");
        }
        if (title.length() > 100) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "标题不能超过 100 字");
        }
        if (remark != null && remark.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "备注不能超过 500 字");
        }
        if (type != null && !List.of("consult", "complaint", "refund", "other").contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "工单类型不合法");
        }
    }

    private Map<String, Object> toVo(CsTicket t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("sessionId", t.getSessionId());
        m.put("customerId", t.getCustomerId());
        m.put("type", t.getType());
        m.put("title", t.getTitle());
        m.put("status", t.getStatus());
        m.put("seatId", t.getSeatId());
        m.put("remark", t.getRemark());
        m.put("slaDueAt", t.getSlaDueAt());
        m.put("resolvedAt", t.getResolvedAt());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }
}
