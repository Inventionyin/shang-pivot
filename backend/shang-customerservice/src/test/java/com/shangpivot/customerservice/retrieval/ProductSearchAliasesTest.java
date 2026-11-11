package com.shangpivot.customerservice.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchAliasesTest {

    @Test
    void 英文商品名生成中文别名() {
        assertThat(ProductSearchAliases.forProductName("HUAWEI Mate X5")).contains("华为").contains("HUAWEI");
        assertThat(ProductSearchAliases.forProductName("iPhone 15 128G")).contains("苹果").contains("iPhone");
        assertThat(ProductSearchAliases.forProductName("Dell U2723QE")).contains("戴尔").contains("Dell");
        assertThat(ProductSearchAliases.forProductName("ThinkPad X1 Carbon")).contains("联想").contains("ThinkPad");
    }

    @Test
    void 中文查询扩展出英文品牌词() {
        assertThat(ProductSearchAliases.expandQuery("手机 华为")).contains("huawei");
        assertThat(ProductSearchAliases.expandQuery("手机 小米")).contains("xiaomi");
        assertThat(ProductSearchAliases.expandQuery("手机 苹果")).contains("iphone");
    }

    @Test
    void 无别名商品不扩展() {
        assertThat(ProductSearchAliases.forProductName("落地台灯")).isEmpty();
        assertThat(ProductSearchAliases.expandQuery("落地台灯")).isEqualTo("落地台灯");
    }
}
