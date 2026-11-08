package com.shangpivot.customerservice.eval;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 数据集结构门禁：ID 唯一、总数、核心 Judge 数量、每条可执行。 */
class GoldenDatasetTest {

    @Test
    void datasetHasTwoHundredUniqueCases() {
        List<GoldenCase> cases = GoldenDataset.all();
        assertThat(cases).hasSize(200);

        Set<String> ids = new HashSet<>();
        cases.forEach(c -> assertThat(ids.add(c.id())).as("duplicate id " + c.id()).isTrue());

        for (int i = 1; i <= 200; i++) {
            String id = i < 100 ? String.format("E%02d-", i) : "E" + i + "-";
            assertThat(ids).as("missing " + id).anyMatch(s -> s.startsWith(id));
        }
    }

    @Test
    void onlyTheTwentyCoreCasesRunJudge() {
        List<GoldenCase> cases = GoldenDataset.all();
        long judgeCases = cases.stream().filter(GoldenCase::judge).count();
        assertThat(judgeCases).isEqualTo(20);
        assertThat(cases.stream().filter(GoldenCase::judge))
                .allMatch(c -> c.id().matches("E(0\\d|1\\d|20).*"));
    }

    @Test
    void everyCaseIsExecutableAndHasARule() {
        for (GoldenCase c : GoldenDataset.all()) {
            assertThat(c.businessRule()).as(c.id()).isNotBlank();
            assertThat(c.turns()).as(c.id()).isNotEmpty();
            assertThat(c.turns()).allMatch(t -> t != null && !t.isBlank());
        }
    }

    @Test
    void newExtendedCasesArePresent() {
        List<String> ids = GoldenDataset.all().stream().map(GoldenCase::id).toList();
        IntStream.rangeClosed(101, 200)
                .forEach(i -> assertThat(ids)
                        .anyMatch(id -> id.startsWith(String.format("E%03d-", i))));
    }
}
