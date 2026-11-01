package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "spu", schema = "commerce", autoResultMap = true)
public class Spu extends BaseEntity {
    private Long categoryId;
    private Long brandId;
    private String name;
    private String subtitle;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> pics;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> videos;
    private String detailHtml;
    private String status;
    private Long soldCount;
    private OffsetDateTime scheduleOnAt;
}
