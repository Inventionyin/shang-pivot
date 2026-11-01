package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 商品切片（数据来自 commerce-api 快照）。embedding 同样走自定义 SQL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "product_chunk", schema = "customerservice")
public class ProductChunk extends BaseEntity {
    private Long spuId;
    private String name;
    private String subtitle;
    private String categoryName;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private Long soldCount;
    private OffsetDateTime refreshedAt;
}
