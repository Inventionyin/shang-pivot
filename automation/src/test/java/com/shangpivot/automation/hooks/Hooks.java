package com.shangpivot.automation.hooks;

import com.shangpivot.automation.config.TestConfig;
import com.shangpivot.automation.context.ScenarioContext;
import com.shangpivot.automation.core.allure.AllureSupport;
import com.shangpivot.automation.core.env.EnvironmentProbe;
import com.shangpivot.automation.core.report.RunSummary;
import com.shangpivot.automation.core.ui.BrowserSession;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景生命周期：预检、按标签启动浏览器、失败留证、数据清理、运行摘要。
 */
public class Hooks {

    private static final Logger log = LoggerFactory.getLogger(Hooks.class);
    private static final ThreadLocal<Long> STARTED_AT = new ThreadLocal<>();
    private static final ThreadLocal<BrowserSession> SESSION = new ThreadLocal<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RunSummary::write, "run-summary-writer"));
    }

    private final ScenarioContext context;

    public Hooks(ScenarioContext context) {
        this.context = context;
    }

    @Before(order = 0)
    public void before(Scenario scenario) {
        EnvironmentProbe.verifyOnce();
        AllureSupport.writeEnvironment();
        MDC.put("scenario", scenario.getName());
        STARTED_AT.set(System.currentTimeMillis());
        log.info("=== 场景开始: {} | tags={}", scenario.getName(), scenario.getSourceTagNames());
        Allure.addAttachment("运行环境", "text/plain", TestConfig.describe(), ".txt");
        Allure.addAttachment("场景标签", "text/plain", String.join(", ", scenario.getSourceTagNames()), ".txt");

        if (scenario.getSourceTagNames().contains("@ui")) {
            BrowserSession session = new BrowserSession();
            session.start(scenario.getName());
            SESSION.set(session);
        }
    }

    @After(order = 1)
    public void afterUiEvidence(Scenario scenario) {
        BrowserSession session = SESSION.get();
        if (session != null) {
            session.finish(scenario.getName(), scenario.isFailed());
            SESSION.remove();
        }
    }

    @After(order = 0)
    public void afterCleanup(Scenario scenario) {
        List<String> problems = new ArrayList<>();
        try {
            problems.addAll(context.cleanup());
        } catch (Exception e) {
            problems.add("清理异常: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - (STARTED_AT.get() == null ? System.currentTimeMillis() : STARTED_AT.get());
        RunSummary.record(scenario.getName(), scenario.getSourceTagNames(), scenario.isFailed(), duration,
                scenario.isFailed() ? "see Allure/cucumber report" : null);
        log.info("=== 场景结束: {} | failed={} | {}ms", scenario.getName(), scenario.isFailed(), duration);
        MDC.remove("scenario");
        STARTED_AT.remove();

        if (!problems.isEmpty()) {
            AllureSupport.attach("清理问题", "text/plain", String.join("\n", problems));
            if (!scenario.isFailed()) {
                throw new AssertionError("场景通过但数据清理失败，按失败处理：\n" + String.join("\n", problems));
            }
        }
    }
}
