package com.shangpivot.customerservice.agent.service;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductSkuDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CartConfirmationPolicyTest {

    private final CartConfirmationPolicy policy = new CartConfirmationPolicy();

    @BeforeEach
    void setUp() throws Exception {
        Field field = CartConfirmationPolicy.class.getDeclaredField("amountThreshold");
        field.setAccessible(true);
        field.set(policy, new BigDecimal("500"));
    }

    @Test
    void lowAmountSingleItemDoesNotRequireConfirmation() {
        assertThat(policy.requiresConfirmation(card(), sku("100"), 1)).isFalse();
    }

    @Test
    void quantityGreaterThanOneRequiresConfirmation() {
        assertThat(policy.requiresConfirmation(card(), sku("100"), 2)).isTrue();
    }

    @Test
    void amountAtThresholdRequiresConfirmation() {
        assertThat(policy.requiresConfirmation(card(), sku("500"), 1)).isTrue();
    }

    private static ProductCardDTO card() {
        return new ProductCardDTO(1L, "测试商品", "", null, new BigDecimal("100"), null, 10L);
    }

    private static ProductSkuDTO sku(String price) {
        return new ProductSkuDTO(1L, 1L, "规格", Map.of(), new BigDecimal(price), "", 10);
    }
}
