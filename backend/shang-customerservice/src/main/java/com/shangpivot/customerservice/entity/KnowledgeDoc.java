package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识文档：仅「发布」可参与检索；向量状态由重建管道流转。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_doc", schema = "customerservice")
public class KnowledgeDoc extends BaseEntity {
    private String title;
    private String category;
    private String content;
    private String status;
    private Integer version;
    private String vectorStatus;
    private Integer chunkCount;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public static final String VECTOR_PENDING = "PENDING";
    public static final String VECTOR_DONE = "DONE";
    public static final String VECTOR_FAILED = "FAILED";
}
