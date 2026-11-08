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

/** 真实数据推送 outbox：与业务同事务写入，定时异步投递到 AI 测试平台。 */
@Data
@TableName(value = "platform_outbox", schema = "customerservice", autoResultMap = true)
public class PlatformOutbox {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventId;

    private String eventType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson;

    /** PENDING/SENDING/SENT/FAILED。 */
    private String status;

    private Integer retryCount;

    private OffsetDateTime nextRetryAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;
}
