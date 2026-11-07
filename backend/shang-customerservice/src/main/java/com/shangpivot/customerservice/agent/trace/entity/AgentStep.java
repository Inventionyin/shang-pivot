package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 图节点 / 子 Agent 步骤。 */
@Data
@TableName(value = "agent_step", schema = "customerservice")
public class AgentStep {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long parentStepId;

    private String node;

    private String agent;

    private String status;

    private String inputSummary;

    private String outputSummary;

    private Integer latencyMs;

    private String checkpointId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
