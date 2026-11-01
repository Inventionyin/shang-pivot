package com.shangpivot.customerservice.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 词法检索的查询预处理（ADR-0006 bigram 口径）。 */
class TextGramsTest {

    @Test
    void 中文应产出bigram() {
        List<String> grams = TextGrams.of("跑步鞋", 10);
        assertThat(grams).containsExactly("跑步", "步鞋");
    }

    @Test
    void 英文数字应产出整词并小写() {
        assertThat(TextGrams.of("iPhone 15", 10)).containsExactly("iphone", "15");
    }

    @Test
    void 混合文本应同时产出词与bigram() {
        List<String> grams = TextGrams.of("蓝牙耳机TWS", 10);
        assertThat(grams).contains("tws");
        assertThat(grams).contains("蓝牙", "牙耳", "耳机");
    }

    @Test
    void 单字退化与上限() {
        assertThat(TextGrams.of("好", 10)).containsExactly("好");
        assertThat(TextGrams.of("跑步鞋", 1)).hasSize(1);
        assertThat(TextGrams.of("   ", 10)).isEmpty();
    }
}
