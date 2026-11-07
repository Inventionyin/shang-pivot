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

/** 一次工具调用。 */
@Data
@TableName(value = "tool_call", schema = "customerservice", autoResultMap = true)
public class ToolCall {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long stepId;

    private String toolName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> argsJson;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultJson;

    /** SUCCESS/FAILED/TIMEOUT/DENIED。 */
    private String status;

    private Integer latencyMs;

    private Integer retryCount;

    private String idempotencyKey;

    private String errorCode;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
