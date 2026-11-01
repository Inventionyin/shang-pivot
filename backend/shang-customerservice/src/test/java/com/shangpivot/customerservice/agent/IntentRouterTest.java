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
}
