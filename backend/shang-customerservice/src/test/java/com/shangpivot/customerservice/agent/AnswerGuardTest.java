package com.shangpivot.customerservice.agent;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.graph.SupervisorAgent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 完整回答事实校验：编造商品/身份/外部平台/购物车必须回退 displayAnswer。 */
class AnswerGuardTest {

    private final AnswerGuard guard = new AnswerGuard();
    private final List<ProductCardDTO> cards = List.of(
            new ProductCardDTO(1L, "Redmi K70 12+256G", "12+256G", null, new BigDecimal("2299"), null, 100L));
    private final String display = "为您找到以下商品：\n【商品1】Redmi K70 12+256G，价格 2299 元";

    @Test
    void fabricatedProductIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.SEARCH_PRODUCTS,
                "以下是可推荐的真实在售商品", cards, display, "推荐 Redmi Turbo 4，约1999元。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).isEqualTo(display);
        assertThat(result.reason()).contains("product");
    }

    @Test
    void identityLeakIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.NONE,
                "", List.of(), display, "我是 Claude，由 Anthropic 开发。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).isEqualTo(display);
    }

    @Test
    void externalPlatformIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.KNOWLEDGE, SupervisorAgent.Action.SEARCH_KNOWLEDGE,
                "《退换货政策》：7 天无理由", List.of(), display, "按京东的退货政策，可以退。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).isEqualTo(display);
    }

    @Test
    void cartQuantityForNoOrderUserIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.ORDER, SupervisorAgent.Action.GET_ORDER,
                "（该会员名下没有订单：如实告知）", List.of(), "您名下当前没有订单。",
                "您名下没有订单，购物车有 4 台商品。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).isEqualTo("您名下当前没有订单。");
    }

    @Test
    void productClaimWithEmptyCardsIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.SEARCH_PRODUCTS,
                "（商品检索无结果）", List.of(), "尚品汇目前没有找到与「自行车」匹配的商品。",
                "为您找到以下商品：双人帐篷。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).doesNotContain("为您找到");
    }

    @Test
    void validAnswerPasses() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.SEARCH_PRODUCTS,
                "以下是可推荐的真实在售商品", cards, display, "Redmi K70 12+256G 售价 2299 元。");

        assertThat(result.replaced()).isFalse();
        assertThat(result.answer()).contains("2299");
    }

    @Test
    void knowledgeAnswerWithoutFactsIsReplaced() {
        String facts = "《退换货政策》：签收后 7 天内可无理由退货";
        AnswerGuard.Result result = guard.guard(AgentName.KNOWLEDGE, SupervisorAgent.Action.SEARCH_KNOWLEDGE,
                facts, List.of(), "根据《退换货政策》：7 天无理由。", "这个我也不清楚。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).startsWith("根据《退换货政策》");
    }

    @Test
    void noProductClaimWithCardsIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.ADD_TO_CART,
                "（用户指代唯一）", cards, display, "当前会话没有可操作的商品，请告诉我具体商品名称。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).isEqualTo(display);
        assertThat(result.reason()).contains("claims no product");
    }

    @Test
    void cartAddedClaimWithoutSuccessFactIsReplaced() {
        AnswerGuard.Result result = guard.guard(AgentName.SHOPPING_GUIDE, SupervisorAgent.Action.ADD_TO_CART,
                "（已生成待确认加购动作，不要声称已加购）", cards,
                "即将加入购物车：Redmi K70 12+256G。请确认是否执行。",
                "已为您加入购物车，可以去结算了。");

        assertThat(result.replaced()).isTrue();
        assertThat(result.answer()).contains("请确认");
        assertThat(result.reason()).contains("claims cart add");
    }
}
