package com.shangpivot.commerce.api;

import java.math.BigDecimal;

/**
 * 商品卡片（与 C 端 toCard 口径一致）：图/名/副标题/价格区间/销量。
 */
public record ProductCardDTO(
        Long spuId,
        String name,
        String subtitle,
        String pic,
        BigDecimal price,
        BigDecimal priceMax,
        Long soldCount
) {
}
