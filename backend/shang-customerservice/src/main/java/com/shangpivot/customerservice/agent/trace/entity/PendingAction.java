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

/** 写操作待确认动作（模型只提计划，用户确认后才执行）。 */
@Data
@TableName(value = "pending_action", schema = "customerservice", autoResultMap = true)
public class PendingAction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    private Long runId;

    private Long customerId;

    private String toolName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> argsJson;

    /** PENDING/CONFIRMED/CANCELLED/EXECUTED/EXPIRED。 */
    private String status;

    private String idempotencyKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultJson;

    private OffsetDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Boolean deleted;
}
