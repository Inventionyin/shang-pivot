package com.shangpivot.customerservice.agent.service;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductSkuDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 加购风险分级：低风险直接执行，数量/金额超过阈值时生成待确认动作。
 * 多规格商品仍先走规格澄清；用户明确规格后按本策略判断是否需要二次确认。
 */
@Component
public class CartConfirmationPolicy {

    @Value("${agent.cart.confirm-amount-threshold:500}")
    private BigDecimal amountThreshold;

    public boolean requiresConfirmation(ProductCardDTO product, ProductSkuDTO sku, int quantity) {
        if (quantity > 1) {
            return true;
        }
        BigDecimal price = sku == null || sku.price() == null ? BigDecimal.ZERO : sku.price();
        BigDecimal total = price.multiply(BigDecimal.valueOf(Math.max(1, quantity)));
        return total.compareTo(amountThreshold) >= 0;
    }

    public BigDecimal amountThreshold() {
        return amountThreshold;
    }
}
