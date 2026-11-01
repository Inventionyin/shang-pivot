package com.shangpivot.ui;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * UI 自动化入口：{@code mvn test}（或 {@code mvn test -Dcucumber.filter.tags="@UI-MALL-CART-001"}）。
 * 配置见 src/test/resources/junit-platform.properties。
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.shangpivot.ui")
public class RunCucumberTest {
}
