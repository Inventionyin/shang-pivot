package com.shangpivot.customerservice.agent.graph;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 转人工策略节点：不调用 LLM，命中高风险/情绪/显式请求直接转人工。
 * 这是第 5 个“角色”，但不算一次模型调用，避免每轮多一跳。
 */
@Component
public class HandoffPolicy {

    private static final Pattern EXPLICIT = Pattern.compile(".*(转人工|人工客服|找人工|真人客服).*");
    // 注意不要用裸“太差/垃圾”：商品描述里“拍照别太差”会误触发转人工
    private static final Pattern NEGATIVE = Pattern.compile(
            ".*(投诉|差评|骗子|欺骗|太差劲|服务太差|垃圾客服|维权|消协|12315|退款纠纷).*");

    public boolean shouldHandoff(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return EXPLICIT.matcher(text).matches() || NEGATIVE.matcher(text).matches();
    }

    public String reason(String text) {
        if (text == null) {
            return "unknown";
        }
        if (EXPLICIT.matcher(text).matches()) {
            return "user_requested";
        }
        return "negative_sentiment_or_high_risk";
    }
}
