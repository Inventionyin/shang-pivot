package com.shangpivot.customerservice.service;

import com.shangpivot.customerservice.entity.CsAuditLog;
import com.shangpivot.customerservice.mapper.CsAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 智能客服审计（全量事件，PRD §8.13）。 */
@Service
@RequiredArgsConstructor
public class CsAuditService {

    private final CsAuditLogMapper auditLogMapper;

    public void record(String event, String risk, Long sessionId, Long customerId, Map<String, Object> detail) {
        CsAuditLog log = new CsAuditLog();
        log.setEvent(event);
        log.setRisk(risk == null ? CsAuditLog.RISK_LOW : risk);
        log.setSessionId(sessionId);
        log.setCustomerId(customerId);
        log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
