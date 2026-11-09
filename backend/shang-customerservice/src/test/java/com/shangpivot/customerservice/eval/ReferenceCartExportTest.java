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

/** 导出全品类指代加购 50 条，供 AI 测试平台按 sourceId upsert。 */
class ReferenceCartExportTest {

    private static final String VERSION = "v35-refcart";
    private static final String SUITE = "reference-cart";

    @Test
    void exportFiftyCases() throws Exception {
        String output = System.getProperty("reference.output", "target/reference-cart-50.jsonl");
        Path path = Path.of(output);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (GoldenCase c : ReferenceCartDataset.all()) {
            lines.add(mapper.writeValueAsString(toRow(c)));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThat(lines).hasSize(50);
        for (String line : lines) {
            assertThat(mapper.readTree(line).path("sourceId").asText()).isNotBlank();
            assertThat(mapper.readTree(line).path("payload").path("suite").asText()).isEqualTo(SUITE);
        }
        System.out.println("[reference-export] wrote " + path.toAbsolutePath());
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
        row.put("tags", List.of("reference:" + c.id(), "suite:" + SUITE));
        row.put("priority", "P0");
        row.put("enabled", true);
        // 平台 agent_test_cases.version 列为 VARCHAR(10)。
        row.put("version", "v35ref");
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
