package com.shangpivot.customerservice.retrieval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 品类/预算解析：必须能约束到生产 category_name 与价格区间。 */
class ProductConstraintTest {

    @Test
    void phoneWithTwoThousandBudgetYieldsCategoryAndRange() {
        ProductConstraints c = ProductConstraintParser.parse("推荐一个手机，预算两千左右");

        assertThat(c.categories()).containsExactly("手机");
        assertThat(c.budgetMin()).isEqualByComparingTo("1500");
        assertThat(c.budgetMax()).isEqualByComparingTo("2500");
        assertThat(c.explicitNewQuery()).isTrue();
    }

    @Test
    void threeThousandFiveMaxIsParsedFromChineseNumeral() {
        ProductConstraints c = ProductConstraintParser.parse("想要续航好、带NFC的手机，预算三千五以内");

        assertThat(c.categories()).containsExactly("手机");
        assertThat(c.budgetMax()).isEqualByComparingTo("3500");
        assertThat(c.budgetMin()).isNull();
    }

    @Test
    void fiveThousandMaxIsParsed() {
        ProductConstraints c = ProductConstraintParser.parse("预算五千以内推荐手机");
        assertThat(c.budgetMax()).isEqualByComparingTo("5000");
    }

    @Test
    void newCategoryWithoutCatalogIsExplicitAndStillConstrained() {
        ProductConstraints c = ProductConstraintParser.parse("你们卖自行车吗");
        assertThat(c.categories()).containsExactly("自行车");
        assertThat(c.explicitNewQuery()).isTrue();
    }

    @Test
    void followUpWithoutNewCategoryDoesNotSetExplicitNewQuery() {
        ProductConstraints c = ProductConstraintParser.parse("它都有哪些规格，有货吗");
        assertThat(c.categories()).isEmpty();
        assertThat(c.explicitNewQuery()).isFalse();
        assertThat(c.hasBudget()).isFalse();
    }

    @Test
    void laptopAliasesMapToProductionCategory() {
        assertThat(ProductConstraintParser.parse("推荐一台办公笔记本").categories())
                .containsExactly("电脑/办公");
        assertThat(ProductConstraintParser.parse("想买游戏本").categories())
                .containsExactly("电脑/办公");
    }

    @Test
    void budgetWithinHandlesMinAndMax() {
        ProductConstraints c = new ProductConstraints(java.util.List.of("手机"),
                new BigDecimal("1500"), new BigDecimal("2500"), "2000 元左右", true);
        assertThat(c.within(new BigDecimal("2299"))).isTrue();
        assertThat(c.within(new BigDecimal("5999"))).isFalse();
        assertThat(c.within(new BigDecimal("999"))).isFalse();
    }

    @Test
    void chineseNumberColloquialForms() {
        assertThat(ProductConstraintParser.number("两千")).isEqualByComparingTo("2000");
        assertThat(ProductConstraintParser.number("三千五")).isEqualByComparingTo("3500");
        assertThat(ProductConstraintParser.number("五千")).isEqualByComparingTo("5000");
    }
}
