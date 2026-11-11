package com.shangpivot.customerservice.agent;

import com.shangpivot.customerservice.agent.reference.ChatCardContextService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ConversationFollowUpServiceTest {

    private final ChatCardContextService context = Mockito.mock(ChatCardContextService.class);
    private final ConversationFollowUpService service = new ConversationFollowUpService(context);

    @Test
    void 推荐列表后的裸序号选中对应商品() {
        when(context.lastAssistantText(1L)).thenReturn("""
                本轮没有检索到新的商品。刚才为您推荐过：
                【商品1】美的 风冷 冰箱，4599 元
                【商品2】海尔 纤诺 洗衣机，3299 元
                回复序号或商品名，我可以继续帮您看规格或加购。""");

        Optional<IntentRouter.Intent> intent = service.resolve(1L, "2");
        assertThat(intent).isPresent();
        assertThat(intent.get().type()).isEqualTo(IntentRouter.Type.ADDCART);
        assertThat(intent.get().productRef()).isEqualTo("海尔 纤诺 洗衣机");
    }

    @Test
    void 澄清序号行解析到第二个商品() {
        when(context.lastAssistantText(2L)).thenReturn("""
                您指的是哪一款？请回复序号：
                1. 索尼 WH-1000XM5
                2. AirPods Pro 2
                以上均为此前推荐过的商品；如需更具体的规格或价格，请先确认款式。""");

        Optional<IntentRouter.Intent> intent = service.resolve(2L, "第二个");
        assertThat(intent).isPresent();
        assertThat(intent.get().productRef()).isEqualTo("AirPods Pro 2");
    }

    @Test
    void 多规格反问后的序号承接商品并保留数量() {
        when(context.lastAssistantText(3L)).thenReturn("""
                《MacBook Air 13 M3 16G》有多个规格，请确认您要哪一款：
                【规格1】16GB，9999 元
                【规格2】24GB，11999 元
                回复规格序号即可为您加购～""");

        Optional<IntentRouter.Intent> intent = service.resolve(3L, "来两台规格2");
        assertThat(intent).isPresent();
        assertThat(intent.get().productRef()).isEqualTo("MacBook Air 13 M3 16G");
        assertThat(intent.get().quantity()).isEqualTo(2);
    }

    @Test
    void 单商品推荐后的确认直接落到该商品() {
        when(context.lastAssistantText(4L)).thenReturn("""
                为您找到以下商品：
                【商品1】美的 风冷 冰箱（多门风冷），价格 4599 元""");

        Optional<IntentRouter.Intent> intent = service.resolve(4L, "确认 就买这个");
        assertThat(intent).isPresent();
        assertThat(intent.get().productRef()).isEqualTo("美的 风冷 冰箱");
    }

    @Test
    void 多商品推荐后的确认不猜具体商品() {
        when(context.lastAssistantText(5L)).thenReturn("""
                为您找到以下商品：
                【商品1】索尼 WH-1000XM5（旗舰降噪耳机），价格 2599 元
                【商品2】AirPods Pro 2（主动降噪），价格 1999 元""");

        Optional<IntentRouter.Intent> intent = service.resolve(5L, "好的");
        assertThat(intent).isPresent();
        assertThat(intent.get().productRef()).isEmpty();
    }

    @Test
    void 是或否确认承接上一轮商品() {
        when(context.lastAssistantText(6L)).thenReturn(
                "您是要加购《美的 风冷 冰箱》吗？回复“确认”继续，或回复“不是”换一款。");

        Optional<IntentRouter.Intent> intent = service.resolve(6L, "确认");
        assertThat(intent).isPresent();
        assertThat(intent.get().productRef()).isEqualTo("美的 风冷 冰箱");
    }

    @Test
    void 普通推荐后的无关注释不触发承接() {
        when(context.lastAssistantText(7L)).thenReturn("为您找到以下商品：\n【商品1】美的 风冷 冰箱，4599 元");
        assertThat(service.resolve(7L, "你好")).isEmpty();
        assertThat(service.resolve(7L, "推荐手机")).isEmpty();
    }
}
