package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一次模型调用（流式结束后聚合为一行）。 */
@Data
@TableName(value = "llm_call", schema = "customerservice")
public class LlmCall {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long stepId;

    private String model;

    private String promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer latencyMs;

    private String finishReason;

    private String status;

    /** 脱敏/截断后的 prompt 摘要或对象存储 key。 */
    private String requestRef;

    /** 脱敏/截断后的响应摘要或对象存储 key。 */
    private String responseRef;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
