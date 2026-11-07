package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/** 护栏事件（注入/敏感输出/越权/限流等）。 */
@Data
@TableName(value = "guardrail_event", schema = "customerservice", autoResultMap = true)
public class GuardrailEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private String type;

    /** BLOCK/MASK/WARN/ESCALATE。 */
    private String action;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
