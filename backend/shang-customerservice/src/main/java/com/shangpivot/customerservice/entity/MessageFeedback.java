package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户对单条 AI 回答的反馈（点赞/点踩 + 原因）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "message_feedback", schema = "customerservice")
public class MessageFeedback extends BaseEntity {
    private Long messageId;
    private Long sessionId;
    private Long customerId;
    private String rating;
    private String reason;
    private String comment;
    private Long runId;
}
