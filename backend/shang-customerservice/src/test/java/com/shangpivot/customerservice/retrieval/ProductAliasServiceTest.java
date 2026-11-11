package com.shangpivot.customerservice.retrieval;

import com.shangpivot.customerservice.entity.ProductAlias;
import com.shangpivot.customerservice.mapper.ProductAliasMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class ProductAliasServiceTest {

    private final ProductAliasMapper mapper = Mockito.mock(ProductAliasMapper.class);

    private ProductAliasService service() {
        Mockito.when(mapper.selectList(any())).thenReturn(List.of(
                alias(1L, "华为", "huawei"),
                alias(2L, "小米", "xiaomi"),
                alias(3L, "苹果", "iphone"),
                alias(4L, "戴尔", "dell"),
                alias(5L, "联想", "thinkpad")
        ));
        return new ProductAliasService(mapper);
    }

    @Test
    void 英文商品名生成中文别名() {
        ProductAliasService service = service();
        assertThat(service.aliasTextFor("HUAWEI Mate X5")).contains("华为");
        assertThat(service.aliasTextFor("iPhone 15 128G")).contains("苹果");
        assertThat(service.aliasTextFor("Dell U2723QE")).contains("戴尔");
        assertThat(service.aliasTextFor("ThinkPad X1 Carbon")).contains("联想");
    }

    @Test
    void 中文查询扩展出英文品牌词() {
        ProductAliasService service = service();
        assertThat(service.expandQuery("手机 华为")).contains("huawei");
        assertThat(service.expandQuery("手机 小米")).contains("xiaomi");
        assertThat(service.expandQuery("手机 苹果")).contains("iphone");
    }

    @Test
    void 无别名商品不扩展() {
        ProductAliasService service = service();
        assertThat(service.aliasTextFor("落地台灯")).isEmpty();
        assertThat(service.expandQuery("落地台灯")).isEqualTo("落地台灯");
    }

    private static ProductAlias alias(long id, String alias, String target) {
        ProductAlias row = new ProductAlias();
        row.setId(id);
        row.setAlias(alias);
        row.setTargetToken(target);
        row.setEnabled(true);
        return row;
    }
}
