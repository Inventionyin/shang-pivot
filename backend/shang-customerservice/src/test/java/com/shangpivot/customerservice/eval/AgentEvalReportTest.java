package com.shangpivot.customerservice.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能客服 Golden 回归（AI Agent 评估实战教程方法论移植）：
 * - 阶段一：每个用例对生产接口完整跑一次（多轮同会话），收集 answer/cards/sources/tokens；
 * - 阶段二：确定性断言（工具正确性/卡片/关键词/禁止模式） + GEval-lite 裁判（可选）；
 * - 输出 target/eval-report.md（分数 + 判词），供「四步法」改进闭环。
 *
 * 运行：mvn test -Dgroups=eval   （确定性断言零 LLM 成本；裁判需 EVAL_LLM_API_KEY，缺失自动跳过）
 * 注意：对生产环境黑盒执行，使用评估专用顾客（13900009999），会话间 1.2s 节流以避开对话限流。
 */
@Tag("eval")
class AgentEvalReportTest {

    private static final double DEFAULT_THRESHOLD = 0.7;
    /** 裁判是否参与门禁：CI 设为 false 仅记录趋势（模型发挥存在波动），本地默认严格。 */
    private static final boolean JUDGE_GATE =
            !"false".equalsIgnoreCase(System.getenv().getOrDefault("EVAL_JUDGE_GATE", "true"));
    /** 裁判参与门禁时，平均分下限：整体质量显著下滑必须失败。 */
    private static final double JUDGE_MIN_AVG =
            Double.parseDouble(System.getenv().getOrDefault("EVAL_JUDGE_MIN_AVG", "0.7"));

    record CaseResult(GoldenCase gc, List<AgentHarness.TurnResult> turns, String lastAnswer,
                      List<String> failures, Double judgeScore, String judgeReason) {
    }

    @Test
    void goldenRegression() throws Exception {
        List<GoldenCase> cases = GoldenDataset.all();
        int caseLimit = Integer.parseInt(System.getenv().getOrDefault("EVAL_CASE_LIMIT", "0"));
        if (caseLimit > 0 && caseLimit < cases.size()) {
            cases = cases.subList(0, caseLimit);
        }
        AgentHarness harness = new AgentHarness();
        LlmJudge judge = new LlmJudge();

        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase gc : cases) {
            results.add(runCase(harness, gc));
            Thread.sleep(1200); // 节流：避开用户级对话限流窗口
        }

        // 阶段二：裁判（与执行分离，评的是同一次回答）
        for (CaseResult r : results) {
            if (r.gc().judge() && judge.available() && r.failures().isEmpty()) {
                String question = String.join(" / ", r.gc().turns());
                LlmJudge.Verdict v = judge.judge(question, r.lastAnswer(), r.gc().evaluationSteps());
                Double score = v.judgeRan() ? v.score() : null;
                String reason = v.judgeRan()
                        ? v.reason() + (score != null && score < r.gc().judgeThreshold() ? "  [低于阈值 " + r.gc().judgeThreshold() + "]" : "")
                        : "裁判跳过: " + v.reason();
                results.set(results.indexOf(r), new CaseResult(r.gc(), r.turns(), r.lastAnswer(),
                        r.failures(), score, reason));
            }
        }

        writeReport(results, judge.available());
        assertSummary(results);
    }

    private CaseResult runCase(AgentHarness harness, GoldenCase gc) throws IOException, InterruptedException {
        // 用例隔离（教程：每个用例独立、可重复）：独立会话 + 账号轮换 + 清空购物车
        harness.start();
        List<AgentHarness.TurnResult> turns = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AgentHarness.TurnResult last = null;
        for (String turn : gc.turns()) {
            try {
                last = harness.send(turn);
                turns.add(last);
            } catch (Exception e) {
                failures.add("执行异常: " + e.getMessage());
                return new CaseResult(gc, turns, last == null ? "" : last.answer(), failures, null, null);
            }
            Thread.sleep(600);
        }
        if (last == null) {
            failures.add("无任何轮次结果");
            return new CaseResult(gc, turns, "", failures, null, null);
        }

        // 参照类：工具正确性（sources 片段）；retrieval 表示向量/词法任一检索路径（模式可降级）
        for (String frag : gc.expectedSourceFragments()) {
            boolean hit = last.sources().stream().anyMatch(s -> "retrieval".equals(frag)
                    ? (s.contains("vector") || s.contains("lexical"))
                    : s.contains(frag));
            if (!hit) {
                failures.add("工具正确性: sources " + last.sources() + " 不含期望片段 '" + frag + "'");
            }
        }
        // 参照类：卡片正确性
        for (String frag : gc.expectedCardFragments()) {
            boolean hit = last.cards().stream().anyMatch(c -> String.valueOf(c.get("name")).contains(frag));
            if (!hit) {
                failures.add("卡片正确性: 未找到含 '" + frag + "' 的卡片，实际 " + last.cards());
            }
        }
        // 参照类：答案关键词
        for (String kw : gc.expectedAnswerKeywords()) {
            if (!last.answer().contains(kw)) {
                failures.add("答案关键词: 缺 '" + kw + "'");
            }
        }
        // 禁止模式（幻觉/PII/思考残留）
        for (String p : gc.forbidPatterns()) {
            if (last.answer().contains(p)) {
                failures.add("禁止模式: 出现 '" + p + "'");
            }
        }
        // 步骤效率：sources 数量上限（防冗余工具/检索调用）
        if (gc.maxSourceCount() != null && last.sources().size() > gc.maxSourceCount()) {
            failures.add("步骤效率: sources " + last.sources().size() + " 超过上限 " + gc.maxSourceCount());
        }
        // 参数正确性：加购类用例校验购物车终态（title 片段 + 精确数量 + 无多余商品）
        if (!gc.expectedCart().isEmpty()) {
            Map<String, Integer> cart = harness.cartState();
            for (GoldenCase.CartExpect expect : gc.expectedCart()) {
                Integer qty = cart.entrySet().stream()
                        .filter(e -> e.getKey().contains(expect.titleFragment()))
                        .mapToInt(java.util.Map.Entry::getValue).sum();
                if (qty != expect.quantity()) {
                    failures.add("参数正确性: 购物车 '" + expect.titleFragment() + "' 期望 " + expect.quantity()
                            + " 实际 " + qty + "（终态 " + cart + "）");
                }
            }
            long unexpected = cart.keySet().stream()
                    .filter(title -> gc.expectedCart().stream().noneMatch(e -> title.contains(e.titleFragment())))
                    .count();
            if (unexpected > 0) {
                failures.add("参数正确性: 购物车出现 " + unexpected + " 项非预期商品（" + cart.keySet() + "）");
            }
        }
        // 降级应答视为失败（除非用例本身验证降级）
        if (last.degraded()) {
            failures.add("本次为降级应答（LLM 不可用或超时），评估无效");
        }
        return new CaseResult(gc, turns, last.answer(), failures, null, null);
    }

    private void writeReport(List<CaseResult> results, boolean judgeAvailable) {
        StringBuilder md = new StringBuilder();
        md.append("# 智能客服 Golden 评估报告\n\n");
        md.append("- 用例数：").append(results.size()).append('\n');
        md.append("- 裁判：").append(judgeAvailable ? "已启用（GEval-lite）" : "未启用（缺 EVAL_LLM_API_KEY，仅确定性断言）").append('\n');
        md.append("- 门禁：确定性断言必须全过；裁判分参与门禁=").append(JUDGE_GATE)
          .append("，平均分下限=").append(JUDGE_MIN_AVG).append('\n');
        md.append("\n## 汇总\n\n| 用例 | 结果 | 裁判分 | sources | 判词/失败原因 |\n|---|---|---|---|---|\n");
        int pass = 0;
        double scoreSum = 0;
        int scoreCount = 0;
        for (CaseResult r : results) {
            boolean ok = r.failures().isEmpty()
                    && (!r.gc().judge() || r.judgeScore() == null || r.judgeScore() >= r.gc().judgeThreshold());
            if (ok) {
                pass++;
            }
            if (r.judgeScore() != null) {
                scoreSum += r.judgeScore();
                scoreCount++;
            }
            String sources = r.turns().isEmpty() ? "-" : String.valueOf(r.turns().get(r.turns().size() - 1).sources().size());
            md.append("| ").append(r.gc().id())
                    .append(" | ").append(ok ? "✅" : "❌")
                    .append(" | ").append(r.judgeScore() == null ? "-" : String.format("%.2f", r.judgeScore()))
                    .append(" | ").append(sources)
                    .append(" | ").append(r.failures().isEmpty() ? r.judgeReason() == null ? "-" : r.judgeReason()
                            : String.join("<br>", r.failures()))
                    .append(" |\n");
        }
        double avg = scoreCount == 0 ? -1 : scoreSum / scoreCount;
        boolean deterministicOk = results.stream().allMatch(r -> r.failures().isEmpty());
        boolean avgOk = !JUDGE_GATE || scoreCount == 0 || avg >= JUDGE_MIN_AVG;
        md.append("\n**严格通过率：").append(pass).append('/').append(results.size())
                .append("；平均裁判分：").append(scoreCount == 0 ? "-" : String.format("%.2f", avg))
                .append("；门禁：").append(deterministicOk && avgOk ? "通过" : "失败")
                .append("**（确定性断言必须全过；裁判分参与门禁=").append(JUDGE_GATE)
                .append("，平均分下限=").append(JUDGE_MIN_AVG).append("）\n");
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/eval-report.md"), md.toString(), StandardCharsets.UTF_8);
            System.out.println("[eval] 报告已写入 target/eval-report.md");
        } catch (IOException e) {
            System.out.println("[eval] 报告写入失败: " + e.getMessage());
        }
        System.out.println(md);
    }

    private void assertSummary(List<CaseResult> results) {
        List<String> bad = new java.util.ArrayList<>();
        for (CaseResult r : results) {
            if (!r.failures().isEmpty()) {
                bad.add(r.gc().id() + ": " + String.join("; ", r.failures()));
            } else if (JUDGE_GATE && r.judgeScore() != null
                    && r.judgeScore() < r.gc().judgeThreshold()) {
                bad.add(r.gc().id() + ": 裁判分 " + r.judgeScore());
            }
        }
        double sum = 0;
        int count = 0;
        for (CaseResult r : results) {
            if (r.judgeScore() != null) {
                sum += r.judgeScore();
                count++;
            }
        }
        if (JUDGE_GATE && count > 0 && sum / count < JUDGE_MIN_AVG) {
            bad.add(String.format("平均裁判分 %.2f 低于下限 %.2f（%d 条有分）", sum / count, JUDGE_MIN_AVG, count));
        }
        assertThat(bad)
                .withFailMessage("Golden 门禁失败（改进四步法：读判词 → 定位层 → 只改一处 → 重跑）:%n%s"
                        .formatted(String.join("\n", bad)))
                .isEmpty();
    }
}
