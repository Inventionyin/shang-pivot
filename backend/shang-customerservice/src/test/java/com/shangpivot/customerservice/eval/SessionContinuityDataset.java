package com.shangpivot.customerservice.eval;

import java.util.List;

/**
 * 会话连续性专项数据集（v33-session）。
 *
 * <p>与 golden-200 分开维护：这 5 条专门覆盖“同一会话内多轮上下文延续”，
 * 不修改原有 200 条的期望值，避免破坏既有基线。UI 层的“进商品详情再返回”
 * 由 automation 的 Playwright 场景覆盖；本数据集覆盖同一会话的 API/Agent 连续性。</p>
 */
public final class SessionContinuityDataset {

    private SessionContinuityDataset() {
    }

    public static List<GoldenCase> all() {
        return List.of(
                GoldenCase.builder("S01-context-specs", "同一会话第二轮追问上文商品规格，必须锚定上文商品")
                        .turns("推荐一个手机，预算两千左右", "Redmi 有什么规格，有货吗")
                        .expectSources("sku:")
                        .efficiency(1)
                        .expectAnswer("2299")
                        .build(),

                GoldenCase.builder("S02-context-addcart", "同一会话第二轮加购上文商品，数量必须精确为 1")
                        .turns("落地台灯 多少钱", "来一个")
                        .expectSources("cart:")
                        .efficiency(1)
                        .expectCards("台灯")
                        .expectCart("台灯", 1)
                        .expectAnswer("购物车")
                        .build(),

                GoldenCase.builder("S03-context-switch", "同一会话中切换上文商品，规格数据不能串到上一个商品")
                        .turns("推荐一个手机，预算两千左右", "那 iPhone 呢，有货吗")
                        .expectSources("sku:")
                        .efficiency(1)
                        .expectAnswer("128G")
                        .build(),

                GoldenCase.builder("S04-context-price", "同一会话中切换问价，回答必须使用新商品价格")
                        .turns("Redmi K70 多少钱", "那 iPhone 15 多少钱")
                        .expectSources("retrieval")
                        .efficiency(3)
                        .expectCards("iPhone 15")
                        .expectAnswer("5999")
                        .build(),

                GoldenCase.builder("S05-context-ambiguous", "同一会话中追问指代不明时必须反问，禁止拿无关商品凑数")
                        .turns("推荐一个手机，预算两千左右", "它都有哪些规格，有货吗？")
                        .expectSources("context-ask")
                        .efficiency(1)
                        .expectCards("Redmi K70", "iPhone 15")
                        .forbid("双人帐篷", "洗衣机", "收纳盒")
                        .build()
        );
    }
}
