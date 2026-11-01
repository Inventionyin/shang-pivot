package com.shangpivot.commerce.api;

import java.math.BigDecimal;

/**
 * 商品索引快照：切片/向量化所需的最小字段集。
 */
public record ProductSnapshotDTO(
        Long spuId,
        String name,
        String subtitle,
        String categoryName,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Long soldCount
) {
}
