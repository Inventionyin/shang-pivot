package com.shangpivot.customerservice.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 全品类上文商品指代加购专项集（v35-refcart，50 条）。
 *
 * <p>30 条覆盖全部在售 SPU（单 SKU 直加/确认，多 SKU 先反问规格）；
 * 10 条覆盖品类/品牌指代；10 条覆盖歧义、iPhone 15/Pro、多规格、无上文、目录外与误判。</p>
 */
public final class ReferenceCartDataset {

    private ReferenceCartDataset() {
    }

    /** 与 GraphGoldenIntegrationTest.CATALOG 对齐的本地 30 SPU（spuId 1..30）。 */
    private record RefProduct(long spuId, String name, String category, String price, boolean multiSku) {
    }

    private static final List<RefProduct> PRODUCTS = List.of(
            new RefProduct(1, "Redmi K70 12+256G", "手机", "2299", false),
            new RefProduct(2, "iPhone 15 128G", "手机", "5999", true),
            new RefProduct(3, "vivo X100", "手机", "5299", false),
            new RefProduct(4, "索尼 WH-1000XM5", "耳机", "2599", false),
            new RefProduct(5, "AirPods Pro 2", "耳机", "1999", false),
            new RefProduct(6, "戴森 V12 吸尘器", "吸尘器", "3999", false),
            new RefProduct(7, "石头 扫拖机器人", "扫地机器人", "2599", false),
            new RefProduct(8, "双人帐篷 防雨款", "帐篷", "599", true),
            new RefProduct(9, "海尔 纤诺 洗衣机", "洗衣机", "3299", false),
            new RefProduct(10, "iPhone 15 Pro", "手机", "7999", true),
            new RefProduct(11, "MacBook Air 13 M3 16G", "电脑", "9999", true),
            new RefProduct(12, "Apple Watch SE", "手表", "2199", false),
            new RefProduct(13, "罗技 MX Master 3s", "外设", "599", false),
            new RefProduct(14, "Xiaomi 14 Pro", "手机", "4999", true),
            new RefProduct(15, "Anker 65W 充电器", "配件", "199", false),
            new RefProduct(16, "HUAWEI Mate X5", "手机", "12999", false),
            new RefProduct(17, "OPPO Find N3", "手机", "9999", false),
            new RefProduct(18, "荣耀 Magic6 Pro", "手机", "6499", false),
            new RefProduct(19, "ThinkPad X1 Carbon", "电脑", "12999", false),
            new RefProduct(20, "ROG 枪神 笔记本", "电脑", "14999", false),
            new RefProduct(21, "联想 拯救者 Y7000", "电脑", "6999", false),
            new RefProduct(22, "Dell U2723QE 27寸 4K", "电脑", "3999", false),
            new RefProduct(23, "美的 风冷 冰箱", "家电", "4599", false),
            new RefProduct(24, "抽屉式收纳盒", "家居收纳", "69", false),
            new RefProduct(25, "落地台灯", "家居收纳", "199", false),
            new RefProduct(26, "温和保湿洁面乳", "美妆个护", "79", false),
            new RefProduct(27, "丝绒雾面口红", "美妆个护", "139", true),
            new RefProduct(28, "每日坚果 750g", "食品生鲜", "89", true),
            new RefProduct(29, "挂耳咖啡 20袋", "食品生鲜", "69", true),
            new RefProduct(30, "减震跑步鞋 男款", "运动户外", "399", true)
    );

    public static List<GoldenCase> all() {
        List<GoldenCase> cases = new ArrayList<>();
        for (RefProduct p : PRODUCTS) {
            cases.add(spuCase(p));
        }
        cases.addAll(categoryAndBrandCases());
        cases.addAll(boundaryCases());
        return List.copyOf(cases);
    }

    private static GoldenCase spuCase(RefProduct p) {
        String fragment = fragment(p.name());
        GoldenCase.Builder builder = GoldenCase.builder("R" + String.format("%02d", p.spuId()) + "-now-buy",
                        "在售 SPU「" + p.name() + "」上文推荐后说“现在买”，必须操作该商品")
                .turns("推荐" + p.name(), "现在买")
                .expectCards(fragment);
        if (p.multiSku()) {
            return builder.expectSources("sku:").efficiency(1)
                    .expectAnswer("规格")
                    .forbid("已为您加入", "即将加入").build();
        }
        if (Integer.parseInt(p.price()) >= 500) {
            return builder.expectSources("cart-confirm:").efficiency(1)
                    .expectAnswer("请确认").build();
        }
        return builder.expectSources("cart:").efficiency(1)
                .expectCart(fragment, 1).expectAnswer("购物车").build();
    }

    private static List<GoldenCase> categoryAndBrandCases() {
        return List.of(
                GoldenCase.builder("R31-category-phone", "品类词“手机”在上文只有一台手机时收窄到该商品")
                        .turns("推荐 vivo X100", "把手机加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("vivo").expectAnswer("请确认").build(),
                GoldenCase.builder("R32-category-computer", "品类词“电脑”在上文只有一台电脑时收窄到该商品")
                        .turns("推荐 ThinkPad 办公本", "把电脑加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("ThinkPad").expectAnswer("请确认").build(),
                GoldenCase.builder("R33-category-earphone-ambiguous", "上文有两款耳机时“把耳机加进购物车”必须反问")
                        .turns("推荐一个降噪耳机", "把耳机加进购物车")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("索尼", "AirPods")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R34-category-watch", "品类词“手表”在上文唯一时收窄到 Apple Watch")
                        .turns("推荐 Apple Watch SE", "把手表加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("Apple Watch").expectAnswer("请确认").build(),
                GoldenCase.builder("R35-brand-huawei", "中文品牌“华为”解析到 HUAWEI 商品")
                        .turns("推荐 HUAWEI Mate X5", "把华为加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("HUAWEI").expectAnswer("请确认").build(),
                GoldenCase.builder("R36-brand-xiaomi", "中文品牌“小米”解析到 Xiaomi 商品")
                        .turns("推荐 Xiaomi 14 Pro", "把小米加进购物车")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("Xiaomi").expectAnswer("规格")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R37-brand-apple-ambiguous", "“苹果”命中多款 Apple 商品时必须反问")
                        .turns("对比 iPhone 15 和 iPhone 15 Pro", "把苹果加进购物车")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("iPhone 15", "iPhone 15 Pro")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R38-brand-lenovo-ambiguous", "“联想”同时命中 ThinkPad 与拯救者时必须反问")
                        .turns("推荐 ThinkPad 和 联想 拯救者", "把联想加进购物车")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("ThinkPad", "拯救者")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R39-brand-dyson", "中文品牌“戴森”解析到戴森商品")
                        .turns("推荐戴森 V12 吸尘器", "把戴森加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("戴森").expectAnswer("请确认").build(),
                GoldenCase.builder("R40-brand-haier", "中文品牌“海尔”解析到海尔商品")
                        .turns("推荐海尔 纤诺 洗衣机", "把海尔加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("海尔").expectAnswer("请确认").build()
        );
    }

    private static List<GoldenCase> boundaryCases() {
        return List.of(
                GoldenCase.builder("R41-second-card", "上文两张卡时“第二个”必须按顺序取第二张")
                        .turns("推荐 Redmi K70 和 vivo X100", "就要第二个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("vivo").expectAnswer("请确认")
                        .forbid("Redmi K70 12+256G").build(),
                GoldenCase.builder("R42-iphone15-not-pro", "“iPhone 15”不得命中 iPhone 15 Pro")
                        .turns("对比 iPhone 15 和 iPhone 15 Pro", "把iPhone 15加进购物车")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("iPhone 15 128G").expectAnswer("规格")
                        .forbid("iPhone 15 Pro").build(),
                GoldenCase.builder("R43-pronoun-ambiguous", "上文两张卡时说“它”必须反问")
                        .turns("推荐索尼 WH-1000XM5 和 AirPods Pro 2", "把它加进购物车")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("索尼", "AirPods")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R44-no-context-name", "无上文时明确点名商品仍可直接加购")
                        .turns("把美的 风冷 冰箱加进购物车")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认").build(),
                GoldenCase.builder("R45-tent-spec", "多规格帐篷未指定规格时必须反问，不写购物车")
                        .turns("来一个双人帐篷")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("帐篷").expectAnswer("规格")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R46-shoes-spec-qty2", "多规格跑步鞋数量 2 也必须先问规格")
                        .turns("来两双减震跑步鞋")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("跑步鞋").expectAnswer("规格")
                        .forbid("已为您加入", "即将加入").build(),
                GoldenCase.builder("R47-low-price-direct", "收纳盒 69 元且数量 1，低于 500 直接加购")
                        .turns("来一个抽屉式收纳盒")
                        .expectSources("cart:").efficiency(1)
                        .expectCards("收纳盒").expectCart("收纳盒", 1).expectAnswer("购物车").build(),
                GoldenCase.builder("R48-high-price-confirm", "冰箱超过 500 元，生成待确认动作")
                        .turns("来一台美的 风冷 冰箱")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认").build(),
                GoldenCase.builder("R49-out-of-catalog", "目录外洗碗机不硬凑商品")
                        .turns("把洗碗机加进购物车")
                        .efficiency(0).expectAnswer("具体商品")
                        .forbid("已为您加入", "即将加入", "为您找到").build(),
                GoldenCase.builder("R50-presale-not-addcart", "“我想买手机”是导购，不是加购")
                        .turns("我想买手机")
                        .expectSources("retrieval").efficiency(3)
                        .forbid("已为您加入", "即将加入").build()
        );
    }

    private static String fragment(String name) {
        if (name.contains(" ")) {
            String[] parts = name.split(" ");
            return parts[0].length() >= 2 ? parts[0] : name;
        }
        return name.length() > 4 ? name.substring(0, 4) : name;
    }
}
