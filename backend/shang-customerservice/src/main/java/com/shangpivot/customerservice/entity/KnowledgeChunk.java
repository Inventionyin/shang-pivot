package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识切片。embedding 列（pgvector）不映射到实体——向量写入/检索走
 * {@link com.shangpivot.customerservice.mapper.KnowledgeChunkMapper} 的自定义 SQL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_chunk", schema = "customerservice")
public class KnowledgeChunk extends BaseEntity {
    private Long docId;
    private Integer seq;
    private String chunkText;
}
