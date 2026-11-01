package com.shangpivot.commerce.api;

import java.math.BigDecimal;
import java.util.Map;

/** SKU 规格（智能客服规格问答 / 加购用）。 */
public record ProductSkuDTO(
        Long skuId,
        Long spuId,
        String title,
        Map<String, Object> specs,
        BigDecimal price,
        String pic,
        int availableStock
) {
}
