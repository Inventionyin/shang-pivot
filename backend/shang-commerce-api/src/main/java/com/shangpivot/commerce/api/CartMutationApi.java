package com.shangpivot.commerce.api;

/**
 * Cross-context cart mutation API (V3.0 智能客服加购工具用)。
 * customerId 显式入参（编排线程无 HTTP 安全上下文）。Implemented by {@code shang-commerce}.
 */
public interface CartMutationApi {

    /** 加入购物车（校验在售 + 库存；重复 SKU 自动叠加数量）。 */
    CartAddResultDTO addToCart(Long customerId, Long skuId, int quantity);
}
