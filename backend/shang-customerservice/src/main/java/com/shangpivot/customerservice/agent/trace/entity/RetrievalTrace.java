package com.shangpivot.customerservice.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** 一次 RAG 检索（表 retrieval）。 */
@Data
@TableName(value = "retrieval", schema = "customerservice", autoResultMap = true)
public class RetrievalTrace {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private String query;

    private Integer topK;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> docIds;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> scores;

    private Integer latencyMs;

    private Boolean hit;

    /** vector/lexical/context。 */
    private String backend;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
