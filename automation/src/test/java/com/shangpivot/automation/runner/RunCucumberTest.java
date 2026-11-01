package com.shangpivot.automation.runner;

import com.shangpivot.automation.core.allure.AllureSupport;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * 统一入口。分层执行示例：
 *
 * <pre>
 * mvn test -Dcucumber.filter.tags="@api"
 * mvn test -Dcucumber.filter.tags="@ui"
 * mvn test -Dcucumber.filter.tags="@smoke"
 * mvn test -Dcucumber.filter.tags="@regression and not @ui"
 * </pre>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.shangpivot.automation")
public class RunCucumberTest {

    static {
        AllureSupport.writeEnvironment();
    }
}
