package com.shangpivot.identity.api;

/**
 * 跨上下文读取系统配置。Implemented by {@code shang-identity}.
 */
public interface SystemConfigApi {

    String getValue(String configKey, String defaultValue);

    int getInt(String configKey, int defaultValue);
}
