package com.shangpivot.identity.api;

import java.util.List;
import java.util.Map;

/**
 * 跨上下文系统配置管理（V3.0 智能客服 AI 配置用）。Implemented by {@code shang-identity}.
 * 仅暴露「按分组读取 + 按 key 更新值」，不开放增删键。
 */
public interface SystemConfigAdminApi {

    /** 按 category 读取配置行：configKey/configValue/isSecret/remark。 */
    List<Map<String, Object>> listByCategory(String category);

    /** 更新配置值；key 不存在返回 false。 */
    boolean updateValue(String configKey, String configValue);
}
