package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/** 消息：USER/ASSISTANT/AGENT/TOOL；卡片走 metadata.items。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "message", schema = "customerservice", autoResultMap = true)
public class CsMessage extends BaseEntity {
    private Long sessionId;
    private String role;
    private String contentType;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    private String reasoning;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> toolTrace;
    private Boolean degraded;

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_AGENT = "AGENT";
    public static final String ROLE_TOOL = "TOOL";

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_PRODUCT_CARDS = "PRODUCT_CARDS";

    /** 卡片 metadata 键：items = List<Map>（与 commerce-api ProductCardDTO 同形）。 */
    public static final String META_ITEMS = "items";
}
