package com.shangpivot.customerservice.retrieval;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品检索硬约束：品类 + 预算。categories 使用 product_chunk.category_name 的精确值。
 * explicitNewQuery=true 表示用户明确提出了新品类/新商品，检索无命中时不得回填上文卡片。
 */
public record ProductConstraints(
        List<String> categories,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String budgetLabel,
        boolean explicitNewQuery
) {

    public ProductConstraints {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static ProductConstraints none() {
        return new ProductConstraints(List.of(), null, null, "", false);
    }

    public boolean hasBudget() {
        return budgetMin != null || budgetMax != null;
    }

    public boolean hasCategories() {
        return !categories.isEmpty();
    }

    public boolean within(BigDecimal price) {
        if (price == null || !hasBudget()) {
            return true;
        }
        if (budgetMax != null && price.compareTo(budgetMax) > 0) {
            return false;
        }
        return budgetMin == null || price.compareTo(budgetMin) >= 0;
    }
}
