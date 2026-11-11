package com.shangpivot.customerservice.eval;

import java.util.List;

/**
 * 多轮澄清与话轮承接专项集（v37-conversation-repair，40 条）。
 *
 * <p>覆盖线上真实断点：口语确认（“确认 就买这个”“就买这个吧”）、澄清后的裸序号、
 * 多规格反问后的规格序号、单候选却反问“哪一款”、以及“没有可操作商品”与卡片并存。</p>
 */
public final class ConversationRepairDataset {

    private ConversationRepairDataset() {
    }

    private static final String GREETING = "我是尚品汇智能客服小尚";
    private static final String NO_CONTEXT = "当前会话没有可操作的商品";

    public static List<GoldenCase> all() {
        return List.of(
                // ---------- 口语确认：单候选必须直接承接 ----------
                GoldenCase.builder("C01-oral-confirm-fridge", "“就买这个吧”承接上文唯一冰箱并生成确认卡")
                        .turns("推荐美的风冷冰箱", "就买这个吧")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C02-confirm-buy-this-fridge", "“确认 就买这个”承接上文唯一冰箱")
                        .turns("推荐美的风冷冰箱", "确认 就买这个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C03-good-buy-this", "“好的，就要这个”承接上文唯一冰箱")
                        .turns("推荐美的风冷冰箱", "好的，就要这个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C04-just-it", "“行，就它”承接上文唯一冰箱")
                        .turns("推荐美的风冷冰箱", "行，就它")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C05-bare-confirm-fridge", "单商品推荐后的“确认”承接该商品")
                        .turns("推荐美的风冷冰箱", "确认")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C06-bare-good-fridge", "单商品推荐后的“好的”承接该商品")
                        .turns("推荐美的风冷冰箱", "好的")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C07-oral-confirm-box", "低价单 SKU 商品口语确认后直接加购")
                        .turns("推荐抽屉式收纳盒", "就买这个吧")
                        .expectSources("cart:").efficiency(1)
                        .expectCart("抽屉式收纳盒", 1).expectAnswer("购物车")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C08-confirm-buy-box", "低价商品“确认 就买这个”直接加购")
                        .turns("推荐抽屉式收纳盒", "确认 就买这个")
                        .expectSources("cart:").efficiency(1)
                        .expectCart("抽屉式收纳盒", 1).expectAnswer("购物车")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C09-bare-confirm-box", "低价商品单候选“确认”直接加购")
                        .turns("推荐抽屉式收纳盒", "确认")
                        .expectSources("cart:").efficiency(1)
                        .expectCart("抽屉式收纳盒", 1).expectAnswer("购物车")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C10-bare-ordinal-box", "低价商品推荐后“1”选中并直加")
                        .turns("推荐抽屉式收纳盒", "1")
                        .expectSources("cart:").efficiency(1)
                        .expectCart("抽屉式收纳盒", 1).expectAnswer("购物车")
                        .forbid(GREETING, NO_CONTEXT).build(),

                // ---------- 歧义列表后的裸序号 ----------
                GoldenCase.builder("C11-ambiguous-then-1", "两款耳机歧义后“1”选中第一款而不是重置")
                        .turns("推荐一个降噪耳机", "把耳机加进购物车", "1")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("索尼").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C12-ambiguous-then-second", "两款耳机歧义后“第二个”选中第二款")
                        .turns("推荐一个降噪耳机", "把耳机加进购物车", "第二个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C13-ambiguous-then-select-2", "两款耳机歧义后“选2”选中第二款")
                        .turns("推荐一个降噪耳机", "把耳机加进购物车", "选2")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C14-buy-first-earphone", "“买第一个”按上文顺序选中索尼")
                        .turns("推荐一个降噪耳机", "买第一个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("索尼").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C15-buy-second-earphone", "“买第二个”按上文顺序选中 AirPods")
                        .turns("推荐一个降噪耳机", "买第二个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C16-first-earphone", "推荐列表后裸“第一个”选中索尼")
                        .turns("推荐一个降噪耳机", "第一个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("索尼").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C17-second-earphone", "推荐列表后裸“第二个”选中 AirPods")
                        .turns("推荐一个降噪耳机", "第二个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C18-bare-1-earphone", "推荐列表后裸“1”选中索尼")
                        .turns("推荐一个降噪耳机", "1")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("索尼").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C19-bare-2-earphone", "推荐列表后裸“2”选中 AirPods")
                        .turns("推荐一个降噪耳机", "2")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C20-confirm-multi-earphone", "多商品推荐后的“确认”必须反问而不是猜")
                        .turns("推荐一个降噪耳机", "确认")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("索尼", "AirPods")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C21-good-multi-earphone", "多商品推荐后的“好的”必须反问")
                        .turns("推荐一个降噪耳机", "好的")
                        .expectSources("context-ask").efficiency(1)
                        .expectCards("索尼", "AirPods")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C22-confirm-then-1", "多商品反问后“1”继续选中第一款")
                        .turns("推荐一个降噪耳机", "确认", "1")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("索尼").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),

                // ---------- 多规格：先反问规格，序号继续承接 ----------
                GoldenCase.builder("C23-macbook-oral-confirm", "多规格商品口语确认后先问规格")
                        .turns("推荐 MacBook Air 13 M3 16G", "就买这个吧")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("MacBook").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C24-macbook-confirm-buy", "多规格商品“确认 就买这个”先问规格")
                        .turns("推荐 MacBook Air 13 M3 16G", "确认 就买这个")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("MacBook").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C25-macbook-bare-1", "多规格商品推荐后“1”选中并问规格")
                        .turns("推荐 MacBook Air 13 M3 16G", "1")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("MacBook").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C26-macbook-sku-2", "问规格后裸“2”选中第二个 SKU 并生成确认")
                        .turns("推荐 MacBook Air 13 M3 16G", "1", "2")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("MacBook").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C27-macbook-sku-1", "问规格后“规格1”选中第一个 SKU")
                        .turns("推荐 MacBook Air 13 M3 16G", "1", "规格1")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("MacBook").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C28-tent-oral-confirm", "帐篷多规格口语确认后先问规格")
                        .turns("推荐双人帐篷 防雨款", "就买这个吧")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("帐篷").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C29-tent-bare-1", "帐篷推荐后“1”选中并问规格")
                        .turns("推荐双人帐篷 防雨款", "1")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("帐篷").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C30-tent-sku-1", "帐篷问规格后“1”选中并生成确认")
                        .turns("推荐双人帐篷 防雨款", "1", "1")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("帐篷").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C31-apple-ambiguous-second", "苹果歧义后“第二个”选中 Pro 并问规格")
                        .turns("对比 iPhone 15 和 iPhone 15 Pro", "把苹果加进购物车", "第二个")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("iPhone 15 Pro").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C32-apple-ambiguous-first", "苹果歧义后“1”选中第一款并先问规格")
                        .turns("对比 iPhone 15 和 iPhone 15 Pro", "把苹果加进购物车", "1")
                        .expectSources("sku:").efficiency(1)
                        .expectCards("iPhone 15").expectAnswer("规格")
                        .forbid(GREETING, NO_CONTEXT, "已为您加入").build(),
                GoldenCase.builder("C33-iphone-qty-two", "数量大于 1 必须生成确认卡")
                        .turns("推荐美的风冷冰箱", "来两台")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C34-box-qty-two", "低价商品数量大于 1 也必须确认")
                        .turns("推荐抽屉式收纳盒", "来两个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("收纳盒").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C35-box-qty-two-tai", "“来两台”数量解析为 2 并确认")
                        .turns("推荐抽屉式收纳盒", "来两台")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("收纳盒").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C36-airpods-qty-two", "AirPods 数量 2 生成确认")
                        .turns("推荐 AirPods Pro 2", "来两台")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C37-airpods-oral-confirm", "AirPods 口语确认生成确认卡")
                        .turns("推荐 AirPods Pro 2", "就买这个吧")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),
                GoldenCase.builder("C38-airpods-bare-confirm", "AirPods 单候选“确认”生成确认卡")
                        .turns("推荐 AirPods Pro 2", "确认")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("AirPods").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build(),

                // ---------- 目录外与完整截图链路 ----------
                GoldenCase.builder("C39-out-of-catalog", "目录外“洗碗机”不得拿上文冰箱凑数")
                        .turns("推荐美的风冷冰箱", "把洗碗机加进购物车")
                        .efficiency(0)
                        .expectAnswer("没有找到")
                        .forbid(GREETING, "美的", "请问哪一款").build(),
                GoldenCase.builder("C40-screenshot-chain", "截图链路：口误 → 上轮商品列表 → “确认 就买这个”仍能加购")
                        .turns("推荐美的风冷冰箱", "就喝一个人吧", "确认 就买这个")
                        .expectSources("cart-confirm:").efficiency(1)
                        .expectCards("美的").expectAnswer("请确认")
                        .forbid(GREETING, NO_CONTEXT).build()
        );
    }
}
