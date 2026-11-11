package com.shangpivot.customerservice.agent.reference;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 30 个在售 SPU × 6 种“上文商品指代”说法的确定性矩阵。
 *
 * <p>这是“所有商品都能加购”的主要证据：每个商品在单卡上下文下，6 种常见说法都必须解析到
 * 自己的 spuId，而不是被重新检索或串到别的商品。</p>
 */
class ProductReferenceResolverTest {

    private static final List<Product> CATALOG = List.of(
            new Product(101, "iPhone 15 Pro 128G", "A17 Pro 芯片，旗舰性能", "手机", "7999"),
            new Product(102, "iPhone 15 128G", "A16 芯片，性能均衡", "手机", "5999"),
            new Product(103, "Xiaomi 14 Pro 12+256G", "徕卡光学，旗舰影像", "手机", "4999"),
            new Product(104, "Redmi K70 12+256G", "性能强悍，价格亲民", "手机", "2299"),
            new Product(105, "HUAWEI Mate X5", "折叠旗舰，大屏体验", "手机", "12999"),
            new Product(106, "OPPO Find N3", "折叠轻薄，旗舰体验", "手机", "9999"),
            new Product(109, "荣耀 Magic6 Pro", "骁龙8 Gen3 旗舰", "手机", "6499"),
            new Product(111, "vivo X100", "自研影像旗舰", "手机", "5299"),
            new Product(121, "MacBook Air 13 M3 16G", "轻薄便携，续航出众", "电脑/办公", "9999"),
            new Product(122, "ThinkPad X1 Carbon", "高可靠商务轻薄", "电脑/办公", "12999"),
            new Product(123, "ROG 枪神 笔记本", "高刷电竞游戏本", "电脑/办公", "14999"),
            new Product(124, "联想 拯救者 Y7000", "高性价比游戏本", "电脑/办公", "6999"),
            new Product(125, "Dell U2723QE 27寸 4K", "Type-C 一线通", "电脑/办公", "3999"),
            new Product(127, "罗技 MX Master 3s", "人体工学鼠标", "电脑/办公", "599"),
            new Product(141, "海尔 纤诺 洗衣机", "大容量变频", "家电", "3299"),
            new Product(142, "美的 风冷 冰箱", "多门风冷", "家电", "4599"),
            new Product(143, "戴森 V12 吸尘器", "强劲吸力", "家电", "3999"),
            new Product(144, "石头 扫拖机器人", "激光导航", "家电", "2599"),
            new Product(161, "索尼 WH-1000XM5", "旗舰降噪耳机", "数码配件", "2599"),
            new Product(162, "AirPods Pro 2", "主动降噪", "数码配件", "1999"),
            new Product(164, "Anker 65W 充电器", "氮化镓快充", "数码配件", "199"),
            new Product(168, "Apple Watch SE", "智能手表", "数码配件", "2199"),
            new Product(181, "抽屉式收纳盒", "桌面收纳", "家居收纳", "69"),
            new Product(184, "落地台灯", "氛围灯", "家居收纳", "199"),
            new Product(201, "温和保湿洁面乳", "氨基酸配方", "美妆个护", "79"),
            new Product(206, "丝绒雾面口红", "显白不拔干", "美妆个护", "139"),
            new Product(221, "每日坚果 750g", "混合坚果，独立小包装", "食品生鲜", "89"),
            new Product(226, "挂耳咖啡 20袋", "阿拉比卡咖啡豆", "食品生鲜", "69"),
            new Product(241, "减震跑步鞋 男款", "适合日常慢跑训练", "运动户外", "399"),
            new Product(246, "双人帐篷 防雨款", "三季适用", "运动户外", "599")
    );

    private static final List<String> PHRASES = List.of(
            "现在买", "买吧", "就这个", "要这个", "把它加进购物车", "上面那个加进购物车"
    );

    private final RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
    private final ProductReferenceResolver resolver = new ProductReferenceResolver(retrievalService);

    @Test
    void 三十个商品六种说法全部解析到自身() {
        for (Product product : CATALOG) {
            for (String phrase : PHRASES) {
                ProductReferenceResolver.Resolution result =
                        resolver.resolve(List.of(product.card()), phrase, "");
                assertThat(result)
                        .as("商品=%s 说法=%s", product.name(), phrase)
                        .isInstanceOf(ProductReferenceResolver.Unique.class);
                assertThat(((ProductReferenceResolver.Unique) result).product().spuId())
                        .as("商品=%s 说法=%s", product.name(), phrase)
                        .isEqualTo(product.spuId());
            }
        }
    }

    @Test
    void 品类词在上文唯一时解析到该品类商品() {
        assertThat(unique(resolver.resolve(List.of(card(102)), "把手机加进购物车", "手机")).spuId())
                .isEqualTo(102L);
        assertThat(unique(resolver.resolve(List.of(card(121)), "把电脑加进购物车", "电脑")).spuId())
                .isEqualTo(121L);
        assertThat(unique(resolver.resolve(List.of(card(162)), "把耳机加进购物车", "耳机")).spuId())
                .isEqualTo(162L);
        assertThat(unique(resolver.resolve(List.of(card(168)), "把手表加进购物车", "手表")).spuId())
                .isEqualTo(168L);
    }

    @Test
    void 品牌别名解析到对应商品() {
        assertThat(unique(resolver.resolve(List.of(card(105)), "把华为加进购物车", "华为")).spuId())
                .isEqualTo(105L);
        assertThat(unique(resolver.resolve(List.of(card(103)), "把小米加进购物车", "小米")).spuId())
                .isEqualTo(103L);
        assertThat(unique(resolver.resolve(List.of(card(102)), "把苹果加进购物车", "苹果")).spuId())
                .isEqualTo(102L);
        assertThat(unique(resolver.resolve(List.of(card(143)), "把戴森加进购物车", "戴森")).spuId())
                .isEqualTo(143L);
        assertThat(unique(resolver.resolve(List.of(card(141)), "把海尔加进购物车", "海尔")).spuId())
                .isEqualTo(141L);
    }

    @Test
    void 多候选时绝不按顺序取第一个() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(102), card(101)), "把手机加进购物车", "手机");
        assertThat(result).isInstanceOf(ProductReferenceResolver.Ambiguous.class);
        assertThat(((ProductReferenceResolver.Ambiguous) result).candidates())
                .extracting(ProductCardDTO::spuId)
                .containsExactlyInAnyOrder(102L, 101L);
    }

    @Test
    void iPhone15优先于iPhone15Pro() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(101), card(102)), "把iPhone 15加进购物车", "iPhone 15");
        assertThat(unique(result).spuId()).isEqualTo(102L);
    }

    @Test
    void 序号指代按上文顺序() {
        assertThat(unique(resolver.resolve(List.of(card(102), card(101)), "就要第二个", "第二个")).spuId())
                .isEqualTo(101L);
    }

    @Test
    void 多个泛称反问而不是串商品() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(161), card(162)), "把它加进购物车", "");
        assertThat(result).isInstanceOf(ProductReferenceResolver.Ambiguous.class);
    }

    @Test
    void 规格快捷选项承接上一轮商品() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(101), card(102)), "规格1", "",
                "《iPhone 15 Pro》有多个规格，请确认您要哪一款：\n【规格1】128GB");
        assertThat(unique(result).spuId()).isEqualTo(101L);
    }

    @Test
    void 无上文时完整型号只命中对应机型() {
        when(retrievalService.searchProducts(anyString(), anyInt(), any())).thenReturn(List.of(
                new RetrievalService.ProductHit(card(101), 0.9, "lexical", true),
                new RetrievalService.ProductHit(card(102), 0.8, "lexical", true)));
        assertThat(unique(resolver.resolve(List.of(), "把iPhone 15 Pro 128G加进购物车", "")).spuId())
                .isEqualTo(101L);
        assertThat(unique(resolver.resolve(List.of(), "把iPhone 15 128G加进购物车", "")).spuId())
                .isEqualTo(102L);
    }

    @Test
    void 规格追问里点名品牌也必须命中() {
        assertThat(unique(resolver.resolve(List.of(card(104)), "Redmi K70 有什么规格", "")).spuId())
                .isEqualTo(104L);
        assertThat(unique(resolver.resolve(List.of(card(101), card(102)), "iPhone 15 Pro 有什么规格", "")).spuId())
                .isEqualTo(101L);
    }

    @Test
    void 语义近邻不应当成目录外商品候选() {
        when(retrievalService.searchProducts(anyString(), anyInt(), any())).thenReturn(List.of(
                new RetrievalService.ProductHit(card(141), 0.8, "vector", true),
                new RetrievalService.ProductHit(card(143), 0.7, "vector", true)));
        ProductReferenceResolver.Resolution result = resolver.resolve(List.of(), "把洗碗机加进购物车", "");
        assertThat(result).isInstanceOf(ProductReferenceResolver.NotFound.class);
    }

    @Test
    void 口语确认与确认词组合解析到唯一商品() {
        List<ProductCardDTO> cards = List.of(card(142));
        assertThat(unique(resolver.resolve(cards, "就买这个吧", "")).spuId()).isEqualTo(142L);
        assertThat(unique(resolver.resolve(cards, "确认 就买这个", "")).spuId()).isEqualTo(142L);
        assertThat(unique(resolver.resolve(cards, "好的，就要这个", "")).spuId()).isEqualTo(142L);
        assertThat(unique(resolver.resolve(cards, "行，就它", "")).spuId()).isEqualTo(142L);
        assertThat(unique(resolver.resolve(cards, "嗯", "")).spuId()).isEqualTo(142L);
    }

    @Test
    void 单候选的规格短追问承接上文() {
        assertThat(unique(resolver.resolve(List.of(card(142)), "有什么规格", "")).spuId())
                .isEqualTo(142L);
        assertThat(unique(resolver.resolve(List.of(card(142)), "有货吗", "")).spuId())
                .isEqualTo(142L);
        assertThat(unique(resolver.resolve(List.of(card(142)), "多少钱", "")).spuId())
                .isEqualTo(142L);
    }

    @Test
    void 目录外商品不拿上文无关卡片凑数() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(142)), "把洗碗机加进购物车", "");
        assertThat(result).isInstanceOf(ProductReferenceResolver.NotFound.class);
    }

    @Test
    void 多候选下的口语确认仍然反问() {
        ProductReferenceResolver.Resolution result = resolver.resolve(
                List.of(card(161), card(162)), "确认 就买这个", "");
        assertThat(result).isInstanceOf(ProductReferenceResolver.Ambiguous.class);
    }

    @Test
    void 无上文时带型号的规格追问也能命中() {
        when(retrievalService.searchProducts(anyString(), anyInt(), any())).thenReturn(List.of(
                new RetrievalService.ProductHit(card(104), 0.9, "lexical", true)));
        assertThat(unique(resolver.resolve(List.of(), "Redmi 有什么规格，有货吗", "")).spuId())
                .isEqualTo(104L);
    }

    private static ProductCardDTO card(long spuId) {
        return CATALOG.stream().filter(p -> p.spuId() == spuId).findFirst().orElseThrow().card();
    }

    private static ProductCardDTO unique(ProductReferenceResolver.Resolution resolution) {
        assertThat(resolution).isInstanceOf(ProductReferenceResolver.Unique.class);
        return ((ProductReferenceResolver.Unique) resolution).product();
    }

    private record Product(long spuId, String name, String subtitle, String category, String price) {
        ProductCardDTO card() {
            return new ProductCardDTO(spuId, name, subtitle, null, new BigDecimal(price), null, 100L, category);
        }
    }
}
