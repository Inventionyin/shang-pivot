package com.shangpivot.commerce.application;

import com.shangpivot.commerce.api.CartAddResultDTO;
import com.shangpivot.commerce.api.CartMutationApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CartMutationApiImpl implements CartMutationApi {

    private final CartService cartService;

    @Override
    public CartAddResultDTO addToCart(Long customerId, Long skuId, int quantity) {
        var result = cartService.addItemFor(customerId, skuId, quantity);
        return new CartAddResultDTO(
                ((Number) result.get("cartItemId")).longValue(),
                skuId,
                ((Number) result.get("quantity")).intValue()
        );
    }
}
