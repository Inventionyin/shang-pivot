package com.shangpivot.customerservice.agent.graph;

import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 子 Agent 提示词：统一护栏 + 角色边界，禁止跨域编造。 */
@Component
@RequiredArgsConstructor
public class AgentPromptFactory {

    private final LlmSettingsResolver settingsResolver;

    public String systemPrompt(AgentName agent) {
        String base = settingsResolver.resolve().getSystemPrompt();
        return base + "\n\n" + switch (agent) {
            case SHOPPING_GUIDE -> """
                    你的角色：导购 Agent。只负责商品推荐、规格/库存解释与加购。
                    规则：只能推荐工具返回的真实商品；不得编造未返回的型号、价格、库存；
                    用户指代不明时先列出候选商品请其确认，禁止拿不相关商品凑数。""";
            case ORDER -> """
                    你的角色：订单 Agent。只负责订单状态、金额、物流、取消/确认收货。
                    规则：只能引用工具返回的当前顾客订单数据；不得查询或猜测他人订单；
                    写操作必须先说明将执行什么，等待用户确认，不得声称已执行。""";
            case AFTER_SALE -> """
                    你的角色：售后 Agent。只负责退换货政策解释、售后申请与工单。
                    规则：政策必须引用知识库片段；售后写操作必须先说明方案并等待用户确认；
                    不确定时引导用户转人工，不得承诺退款金额或时效。""";
            case KNOWLEDGE -> """
                    你的角色：知识 Agent。只负责 FAQ、活动规则、政策问答。
                    规则：只依据知识片段回答，引用《标题》；没有命中时如实说不知道，建议换问法或转人工；
                    不得编造政策、价格、活动。""";
            case HANDOFF -> "你的角色：转人工策略。不要生成回答，只输出转人工原因。";
        };
    }

    public String promptVersion(AgentName agent) {
        return "v31-" + agent.name().toLowerCase();
    }
}
