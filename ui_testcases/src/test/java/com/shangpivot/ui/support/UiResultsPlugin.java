package com.shangpivot.ui.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.ui.config.Env;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把场景结果落成与 api_testcases 同构的 results 文件，便于用同一套回写器同步禅道。
 * 同时绑定 run_id / started_at / finished_at —— 结果可以被证明属于哪一轮（防"拿旧结果充数"）。
 * <p>
 * 用法（junit-platform.properties 里配）：{@code com.shangpivot.ui.support.UiResultsPlugin:artifacts/automation_results_ui.json}
 */
public class UiResultsPlugin implements ConcurrentEventListener {

    /** 只认稳定 ID 形式的标签：@UI-MALL-LOGIN-001。 */
    private static final Pattern STABLE_ID = Pattern.compile("^UI-[A-Z0-9]+(-[A-Z0-9]+)*$");

    private final Path output;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private String startedAt;

    public UiResultsPlugin(String output) {
        this.output = Path.of(output == null || output.isBlank()
                ? Env.ARTIFACTS.resolve("automation_results_ui.json").toString()
                : output);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, event ->
                startedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, this::onRunFinished);
    }

    private void onCaseFinished(TestCaseFinished event) {
        Result result = event.getResult();
        List<String> caseIds = event.getTestCase().getTags().stream()
                .map(tag -> tag.startsWith("@") ? tag.substring(1) : tag)
                .filter(tag -> STABLE_ID.matcher(tag).matches())
                .toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeid", event.getTestCase().getUri() + ":" + event.getTestCase().getLocation().getLine()
                + " " + event.getTestCase().getName());
        row.put("outcome", mapStatus(result.getStatus()));
        row.put("case_ids", caseIds);
        row.put("duration_seconds", result.getDuration().toMillis() / 1000.0);
        row.put("evidence_json", result.getStatus() == Status.PASSED ? "" : evidenceHint(event));
        results.add(row);
    }

    private void onRunFinished(TestRunFinished event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "cucumber-ui");
        payload.put("run_id", Artifacts.runId());
        payload.put("started_at", startedAt == null ? "" : startedAt);
        payload.put("finished_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        payload.put("base_url", Env.MALL_URL);
        payload.put("admin_url", Env.ADMIN_URL);
        payload.put("scenario_count", results.size());
        payload.put("results", results);
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), payload);
            // 跑批摘要同时落一份到留证目录，便于"这一轮跑了什么"一眼可查
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(Artifacts.file("", "run", ".json").toFile(), payload);
            System.out.println("[ui-results] 已写出 " + output.toAbsolutePath()
                    + "（场景 " + results.size() + " 个）");
        } catch (Exception e) {
            throw new IllegalStateException("写结果文件失败：" + output, e);
        }
        if (results.isEmpty()) {
            // 空结果 = 回写链断，绝不能让"什么都没跑"显示为成功
            throw new IllegalStateException("UI 套件没有执行任何场景（results 为空）——检查 features 路径与 tag 过滤条件");
        }
    }

    /** 失败场景的取证位置：artifacts/ui/<runId>/failures/<场景>.{png,html,zip}。 */
    private String evidenceHint(TestCaseFinished event) {
        return Artifacts.dir(Artifacts.FAILURES).resolve("（同名 png / html / zip）").toString();
    }

    private String mapStatus(Status status) {
        return switch (status) {
            case PASSED -> "passed";
            case FAILED, AMBIGUOUS -> "failed";
            case SKIPPED -> "skipped";
            case PENDING, UNDEFINED -> "blocked";
            // 未知状态一律按失败处理：宁可红，不可假绿
            default -> "failed";
        };
    }
}
