package com.shangpivot.commerce.api;

/** 加购结果。 */
public record CartAddResultDTO(
        Long cartItemId,
        Long skuId,
        int quantity
) {
}
