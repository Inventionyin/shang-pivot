package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 一次用户任务触发的 Agent 运行（customer service 运行轨迹主表）。 */
@Data
@TableName(value = "agent_run", schema = "customerservice")
public class AgentRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long sessionId;

    private Long assistantMessageId;

    private Long customerId;

    private String channel;

    private String intent;

    private String agent;

    /** RUNNING/OK/NEED_CONFIRMATION/HANDOFF/FAILED。 */
    private String status;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    private Long totalTokens;

    private BigDecimal totalCost;

    private String error;

    private String model;

    private String promptVersion;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Boolean deleted;
}
