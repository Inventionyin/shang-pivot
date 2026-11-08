package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/** 会话：状态机见 docs/04-architecture/v3.0/数据模型.md。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "chat_session", schema = "customerservice")
public class ChatSession extends BaseEntity {
    private Long customerId;
    private String clientType;
    private String status;
    private Long seatId;
    private String summary;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    /** 平台测试流量标记；生产会话为 false。 */
    private Boolean isTest;
    /** ai=平台自动化测试；human=平台人工体验。 */
    private String testSource;
    private String testRunId;

    public static final String STATUS_AI_ONGOING = "AI_ONGOING";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_HUMAN_ONGOING = "HUMAN_ONGOING";
    public static final String STATUS_ENDED = "ENDED";
}
