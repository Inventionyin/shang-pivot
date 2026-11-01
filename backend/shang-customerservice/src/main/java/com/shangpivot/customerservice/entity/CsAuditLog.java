package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/** 智能客服审计：会话/工具调用/转人工/护栏事件全量记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "audit_log", schema = "customerservice", autoResultMap = true)
public class CsAuditLog extends BaseEntity {
    private Long sessionId;
    private Long customerId;
    private String event;
    private String risk;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MID = "MID";
    public static final String RISK_HIGH = "HIGH";
}
