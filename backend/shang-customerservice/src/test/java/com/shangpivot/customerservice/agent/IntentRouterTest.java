package com.shangpivot.customerservice.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.customerservice.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 意图路由规则回落路径（LLM 不可用/未命中时的确定性口径）。 */
class IntentRouterTest {

    /** 永远抛异常的 LLM 桩：验证规则回落。 */
    private static final LlmClient BROKEN_LLM = new LlmClient() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public StreamResult streamChat(String systemPrompt, List<Map<String, String>> messages, StreamListener listener) {
            throw new LlmException("stub");
        }

        @Override
        public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
            throw new LlmException("stub");
        }

        @Override
        public List<String> listModels() {
            throw new LlmException("stub");
        }
    };

    private final IntentRouter router = new IntentRouter(BROKEN_LLM, new ObjectMapper());

    @Test
    void 订单类消息走订单意图() {
        var intent = router.route("帮我查一下物流到哪了");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ORDER);
        assertThat(intent.byRule()).isTrue();
    }

    @Test
    void 带订单号的查询应提取单号() {
        var intent = router.route("订单 SP20261030001 发货了吗");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ORDER);
        assertThat(intent.orderNo()).isEqualTo("SP20261030001");
    }

    @Test
    void 退换货走售后意图() {
        assertThat(router.route("怎么申请退货退款").type()).isEqualTo(IntentRouter.Type.AFTERSALE);
    }

    @Test
    void 其余消息回落售前并抽取关键词() {
        var intent = router.route("我想买一双跑步鞋，请帮我推荐");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.PRESALE);
        assertThat(intent.keyword()).doesNotContain("我想买");
    }

    @Test
    void 序数指代商品时数量必须为一() {
        var second = router.route("就买第二个");
        assertThat(second.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(second.quantity()).isEqualTo(1);
        var first = router.route("帮我购买第一款");
        assertThat(first.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(first.quantity()).isEqualTo(1);
    }

    @Test
    void 数量短句走加购意图并解析中文数量() {
        var intent = router.route("来两台");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(intent.quantity()).isEqualTo(2);
        assertThat(intent.byRule()).isTrue();
    }

    @Test
    void 再来与拿三个同样识别为加购() {
        assertThat(router.route("再来一件").type()).isEqualTo(IntentRouter.Type.ADDCART);
        var intent = router.route("拿三个");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(intent.quantity()).isEqualTo(3);
    }

    @Test
    void 句首数量但带推荐语义时不误判加购() {
        assertThat(router.route("来一台手机推荐").type()).isEqualTo(IntentRouter.Type.PRESALE);
    }

    @Test
    void 商品名里的数字不被当作购买数量() {
        var intent = router.route("来一台 HUAWEI Mate X5");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(intent.quantity()).isEqualTo(1);

        assertThat(router.route("来一台 iPhone 15").quantity()).isEqualTo(1);
        assertThat(router.route("来2台 iPhone 15").quantity()).isEqualTo(2);
    }

    @Test
    void 加购数量前缀不进入检索关键词() {
        assertThat(router.route("来三个收纳盒").keyword()).isEqualTo("收纳盒");
        assertThat(router.route("来一台 HUAWEI Mate X5").keyword()).doesNotContain("来一台");
    }

    @Test
    void 对比与二选一不被属性词误判为单商品规格() {
        assertThat(router.route("戴森 V12 和 石头 扫拖机器人 哪个吸力好").type())
                .isEqualTo(IntentRouter.Type.PRESALE);
        assertThat(router.route("AirPods Pro 2 和 索尼 WH-1000XM5 哪个降噪好").type())
                .isEqualTo(IntentRouter.Type.PRESALE);
        assertThat(router.route("iPhone 15 和 iPhone 15 Pro 哪个贵").type())
                .isEqualTo(IntentRouter.Type.PRESALE);
    }

    @Test
    void 明确购买上文第N款识别为加购() {
        assertThat(router.route("帮我购买第一款").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("就买第二个").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("我要这个").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("买它").type()).isEqualTo(IntentRouter.Type.ADDCART);
    }

    @Test
    void 加进购物车句式必须走加购而不是重新检索() {
        var name = router.route("帮我把上面那个美的风冷冰箱加进购物车");
        assertThat(name.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(name.productRef()).contains("美的");

        var pronoun = router.route("把上面那个加进购物车");
        assertThat(pronoun.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(pronoun.productRef()).isBlank();

        var confirm = router.route("我现在买");
        assertThat(confirm.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(confirm.quantity()).isEqualTo(1);
        assertThat(router.route("现我买").type()).isEqualTo(IntentRouter.Type.ADDCART);
    }

    @Test
    void 纯指示代词确认识别为加购() {
        assertThat(router.route("就这个").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("这个吧").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("那就这个吧").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("就它了").type()).isEqualTo(IntentRouter.Type.ADDCART);
    }

    @Test
    void 指示代词问价不误判加购() {
        assertThat(router.route("这个多少钱").type()).isNotEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("这款有什么颜色").type()).isNotEqualTo(IntentRouter.Type.ADDCART);
    }

    @Test
    void 来两双跑步鞋识别为加购且数量为二() {
        var intent = router.route("来两双减震跑步鞋");
        assertThat(intent.type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(intent.quantity()).isEqualTo(2);
    }

    @Test
    void 多规格快捷选项规格一继续加购() {
        assertThat(router.route("规格1").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("规格2").type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(router.route("选3").type()).isEqualTo(IntentRouter.Type.ADDCART);
    }

    @Test
    void 购买确认短语不误伤普通导购() {
        assertThat(router.route("我想买冰箱").type()).isEqualTo(IntentRouter.Type.PRESALE);
        assertThat(router.route("购物车怎么结算").type()).isNotEqualTo(IntentRouter.Type.ADDCART);
    }
}
