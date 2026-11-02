package com.shangpivot.customerservice.eval;

import java.util.List;

/**
 * 一条 Golden 用例（AI Agent 评估实战教程 §5 方法论）：
 * 每条必须对应一条业务规则；expected* 只写结论要点，不写整句；
 * evaluationSteps 供 LLM 裁判使用（2~4 条）；judge=false 时只打分不判定（threshold 降级，§6）。
 *
 * @param id                     用例 ID
 * @param businessRule           对应的业务规则（追溯锚点）
 * @param turns                  多轮用户输入（同会话顺序发送）
 * @param expectedSourceFragments 最终轮 sources 必须包含的片段（工具正确性；如 "vector"、"sku:"、"cart:"）
 * @param maxSourceCount         步骤效率：最终轮 sources 数量上限（null = 不设限；闲聊应为 0）
 * @param expectedCardFragments  最终轮卡片名必须包含的片段（可空）
 * @param expectedCart           参数正确性：用例结束后购物车终态（title 片段 + 精确数量；空 = 不校验购物车）
 * @param expectedAnswerKeywords 答案必须包含的关键词（可空）
 * @param forbidPatterns         答案禁止出现的模式（如完整手机号、英文思考残留）
 * @param judge                  是否启用 LLM 裁判（false = 只跑确定性断言）
 * @param judgeThreshold         裁判通过线（默认 0.7）
 * @param evaluationSteps        裁判评分步骤（2~4 条）
 */
public record GoldenCase(
        String id,
        String businessRule,
        List<String> turns,
        List<String> expectedSourceFragments,
        Integer maxSourceCount,
        List<String> expectedCardFragments,
        List<CartExpect> expectedCart,
        List<String> expectedAnswerKeywords,
        List<String> forbidPatterns,
        boolean judge,
        double judgeThreshold,
        List<String> evaluationSteps
) {

    /** 购物车终态期望（参数正确性断言）。 */
    public record CartExpect(String titleFragment, int quantity) {
    }

    public static Builder builder(String id, String businessRule) {
        return new Builder(id, businessRule);
    }

    public static class Builder {
        private final String id;
        private final String businessRule;
        private List<String> turns;
        private List<String> expectedSourceFragments = List.of();
        private Integer maxSourceCount;
        private List<String> expectedCardFragments = List.of();
        private List<CartExpect> expectedCart = List.of();
        private List<String> expectedAnswerKeywords = List.of();
        private List<String> forbidPatterns = List.of();
        private boolean judge = false;
        private double judgeThreshold = 0.7;
        private List<String> evaluationSteps = List.of();

        Builder(String id, String businessRule) {
            this.id = id;
            this.businessRule = businessRule;
        }

        public Builder turns(String... turns) {
            this.turns = List.of(turns);
            return this;
        }

        public Builder expectSources(String... fragments) {
            this.expectedSourceFragments = List.of(fragments);
            return this;
        }

        public Builder efficiency(int maxSources) {
            this.maxSourceCount = maxSources;
            return this;
        }

        public Builder expectCards(String... fragments) {
            this.expectedCardFragments = List.of(fragments);
            return this;
        }

        public Builder expectCart(String titleFragment, int quantity) {
            this.expectedCart = List.of(new CartExpect(titleFragment, quantity));
            return this;
        }

        public Builder expectAnswer(String... keywords) {
            this.expectedAnswerKeywords = List.of(keywords);
            return this;
        }

        public Builder forbid(String... patterns) {
            this.forbidPatterns = List.of(patterns);
            return this;
        }

        public Builder judge(double threshold, String... steps) {
            this.judge = true;
            this.judgeThreshold = threshold;
            this.evaluationSteps = List.of(steps);
            return this;
        }

        public GoldenCase build() {
            return new GoldenCase(id, businessRule, turns, expectedSourceFragments, maxSourceCount,
                    expectedCardFragments, expectedCart, expectedAnswerKeywords, forbidPatterns,
                    judge, judgeThreshold, evaluationSteps);
        }
    }
}
