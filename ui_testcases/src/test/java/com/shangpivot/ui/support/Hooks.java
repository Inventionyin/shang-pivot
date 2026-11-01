package com.shangpivot.ui.support;

import com.shangpivot.ui.config.Env;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

import java.util.List;

/**
 * 场景生命周期：起会话 → 跑 → 收尾清理 → 留证（截图成败都有）。
 * 清理失败要让场景红（不允许"跑完留下一堆测试数据"当成通过）。
 */
public class Hooks {

    private static final java.util.concurrent.atomic.AtomicBoolean PREFLIGHT_DONE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final BrowserSession session;
    private final ScenarioContext context;

    public Hooks(BrowserSession session, ScenarioContext context) {
        this.session = session;
        this.context = context;
    }

    @Before(order = 0)
    public void startSession(Scenario scenario) {
        // 首次进入先做一次环境自检：站点不可达就明确失败，绝不静默跳过（否则"站点挂了"会显示成全绿）
        if (PREFLIGHT_DONE.compareAndSet(false, true)) {
            Env.preflight();
        }
        session.start(scenario.getName());
    }

    @After(order = 0)
    public void cleanupThenClose(Scenario scenario) {
        // 先清理（只走接口，不动页面），保证失败截图拍到的是失败当下的页面
        List<String> problems = context.cleanup();
        boolean failed = scenario.isFailed();
        byte[] screenshot = session.finish(scenario.getName(), failed);

        if (screenshot != null) {
            scenario.attach(screenshot, "image/png", failed ? "失败截图" : "执行截图");
        }
        scenario.log("造数登记：" + context.snapshot());

        if (!problems.isEmpty() && !failed) {
            throw new IllegalStateException("场景通过但清理未完成（数据泄漏）：" + String.join("；", problems));
        }
        problems.forEach(p -> System.err.println("[ui-cleanup] " + p));
    }
}
