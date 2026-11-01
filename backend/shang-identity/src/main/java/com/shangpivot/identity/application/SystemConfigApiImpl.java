package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.identity.api.SystemConfigApi;
import com.shangpivot.identity.entity.SystemConfig;
import com.shangpivot.identity.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SystemConfigApiImpl implements SystemConfigApi {

    private final SystemConfigMapper systemConfigMapper;

    @Override
    public String getValue(String configKey, String defaultValue) {
        SystemConfig cfg = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, configKey));
        if (cfg == null || !StringUtils.hasText(cfg.getConfigValue())) {
            return defaultValue;
        }
        return cfg.getConfigValue();
    }

    @Override
    public int getInt(String configKey, int defaultValue) {
        String v = getValue(configKey, null);
        if (!StringUtils.hasText(v)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
