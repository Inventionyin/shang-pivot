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

/**
 * 把 Java GoldenDataset 导出为平台可导入的 JSONL。
 * 默认写 target/golden-200.jsonl；可用 -Dgolden.output=... 指定实际交付路径。
 */
class GoldenJsonlExportTest {

    private static final String VERSION = "v32-200";

    @Test
    void exportTwoHundredCases() throws Exception {
        String output = System.getProperty("golden.output", "target/golden-200.jsonl");
        Path path = Path.of(output);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (GoldenCase c : GoldenDataset.all()) {
            lines.add(mapper.writeValueAsString(toRow(c)));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThat(lines).hasSize(200);
        for (String line : lines) {
            assertThat(mapper.readTree(line).path("sourceId").asText()).isNotBlank();
        }
        System.out.println("[golden-export] wrote " + path.toAbsolutePath());
    }

    private Map<String, Object> toRow(GoldenCase c) {
        boolean core = c.judge();
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
                "suite", core ? "core" : "extended"));
        row.put("expected", expected(c));
        row.put("checks", checks(c));
        row.put("tags", tags(c));
        row.put("priority", core ? "P0" : "P1");
        row.put("enabled", true);
        row.put("version", VERSION);
        return row;
    }

    private Map<String, Object> expected(GoldenCase c) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("sourceFragments", c.expectedSourceFragments());
        if (c.maxSourceCount() != null) {
            expected.put("maxSourceCount", c.maxSourceCount());
        }
        expected.put("cardFragments", c.expectedCardFragments());
        expected.put("cart", cart(c));
        expected.put("answerKeywords", c.expectedAnswerKeywords());
        expected.put("forbidPatterns", c.forbidPatterns());
        if (c.judge()) {
            expected.put("judge", Map.of(
                    "enabled", true,
                    "threshold", c.judgeThreshold(),
                    "steps", c.evaluationSteps()));
        }
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
        for (Map<String, Object> cart : cart(c)) {
            checks.add(check("cart_quantity", Map.of(
                    "title", cart.get("titleFragment"),
                    "titleFragment", cart.get("titleFragment"),
                    "quantity", cart.get("quantity"))));
        }
        checks.add(check("degraded_false", true));
        return checks;
    }

    private List<Map<String, Object>> cart(GoldenCase c) {
        return c.expectedCart().stream()
                .map(item -> Map.<String, Object>of(
                        "titleFragment", item.titleFragment(),
                        "quantity", item.quantity()))
                .toList();
    }

    private List<String> tags(GoldenCase c) {
        List<String> tags = new ArrayList<>();
        tags.add("golden:" + c.id());
        tags.add("suite:" + (c.judge() ? "core" : "extended"));
        if (c.judge()) {
            tags.add("judge");
        }
        return tags;
    }

    private Map<String, Object> check(String type, Object expected) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("type", type);
        check.put("expected", expected);
        return check;
    }
}
