package com.shangpivot.customerservice.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 导出会话连续性 5 条数据集，供 AI 测试平台按 sourceId upsert。 */
class SessionContinuityExportTest {

    private static final String VERSION = "v33-session";
    private static final String SUITE = "session-continuity";

    @Test
    void exportFiveCases() throws Exception {
        String output = System.getProperty("session.output", "target/session-continuity-5.jsonl");
        Path path = Path.of(output);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (GoldenCase c : SessionContinuityDataset.all()) {
            lines.add(mapper.writeValueAsString(toRow(c)));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThat(lines).hasSize(5);
        for (String line : lines) {
            assertThat(mapper.readTree(line).path("sourceId").asText()).isNotBlank();
            assertThat(mapper.readTree(line).path("payload").path("suite").asText()).isEqualTo(SUITE);
        }
        System.out.println("[session-export] wrote " + path.toAbsolutePath());
    }

    private Map<String, Object> toRow(GoldenCase c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", c.id() + " · " + c.businessRule());
        row.put("sourceId", c.id());
        row.put("datasetVersion", VERSION);
        row.put("inputType", "text");
        row.put("command", c.turns().isEmpty() ? "" : c.turns().get(0));
        row.put("turns", c.turns());
        row.put("payload", Map.of(
                "sourceId", c.id(),
                "datasetVersion", VERSION,
                "businessRule", c.businessRule(),
                "suite", SUITE));
        row.put("expected", expected(c));
        row.put("checks", checks(c));
        row.put("tags", List.of("session:" + c.id(), "suite:" + SUITE));
        row.put("priority", "P1");
        row.put("enabled", true);
        // 平台 agent_test_cases.version 列为 VARCHAR(10)，数据集版本放在 payload.datasetVersion。
        row.put("version", "v33-sess");
        return row;
    }

    private Map<String, Object> expected(GoldenCase c) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("sourceFragments", c.expectedSourceFragments());
        if (c.maxSourceCount() != null) {
            expected.put("maxSourceCount", c.maxSourceCount());
        }
        expected.put("cardFragments", c.expectedCardFragments());
        expected.put("cart", c.expectedCart().stream()
                .map(item -> Map.of("titleFragment", item.titleFragment(), "quantity", item.quantity()))
                .toList());
        expected.put("answerKeywords", c.expectedAnswerKeywords());
        expected.put("forbidPatterns", c.forbidPatterns());
        return expected;
    }

    private List<Map<String, Object>> checks(GoldenCase c) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (String source : c.expectedSourceFragments()) {
            checks.add(check("source_contains", source));
        }
        if (c.maxSourceCount() != null) {
            checks.add(check("max_sources", c.maxSourceCount()));
        }
        for (String card : c.expectedCardFragments()) {
            checks.add(check("card_contains", card));
        }
        for (String keyword : c.expectedAnswerKeywords()) {
            checks.add(check("contains", keyword));
        }
        for (String pattern : c.forbidPatterns()) {
            checks.add(check("forbid_pattern", pattern));
        }
        for (GoldenCase.CartExpect cart : c.expectedCart()) {
            checks.add(check("cart_quantity", Map.of(
                    "title", cart.titleFragment(),
                    "titleFragment", cart.titleFragment(),
                    "quantity", cart.quantity())));
        }
        checks.add(check("degraded_false", true));
        return checks;
    }

    private Map<String, Object> check(String type, Object expected) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("type", type);
        check.put("expected", expected);
        return check;
    }
}
