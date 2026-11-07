package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 用户反馈（点赞/点踩/转人工/解决），P1 使用。 */
@Data
@TableName(value = "feedback", schema = "customerservice")
public class Feedback {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long sessionId;

    private Long customerId;

    private String type;

    private Integer score;

    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
