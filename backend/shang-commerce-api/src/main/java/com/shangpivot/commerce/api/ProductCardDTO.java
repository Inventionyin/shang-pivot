package com.shangpivot.commerce.api;

import java.math.BigDecimal;

/**
 * 商品卡片（与 C 端 toCard 口径一致）：图/名/副标题/价格区间/销量/类目。
 *
 * <p>categoryName 用于对话内指代解析（“把手机加进购物车”），不要求 C 端展示；
 * 旧调用方可继续使用 7 参数构造器，类目为 null。</p>
 */
public record ProductCardDTO(
        Long spuId,
        String name,
        String subtitle,
        String pic,
        BigDecimal price,
        BigDecimal priceMax,
        Long soldCount,
        String categoryName
) {

    public ProductCardDTO(Long spuId, String name, String subtitle, String pic,
                          BigDecimal price, BigDecimal priceMax, Long soldCount) {
        this(spuId, name, subtitle, pic, price, priceMax, soldCount, null);
    }
}
