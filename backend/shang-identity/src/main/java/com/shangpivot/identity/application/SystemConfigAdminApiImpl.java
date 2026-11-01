package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.identity.api.SystemConfigAdminApi;
import com.shangpivot.identity.entity.SystemConfig;
import com.shangpivot.identity.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SystemConfigAdminApiImpl implements SystemConfigAdminApi {

    private final SystemConfigMapper systemConfigMapper;

    @Override
    public List<Map<String, Object>> listByCategory(String category) {
        return systemConfigMapper.selectList(new LambdaQueryWrapper<SystemConfig>()
                        .eq(SystemConfig::getCategory, category)
                        .orderByAsc(SystemConfig::getId))
                .stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("configKey", c.getConfigKey());
                    m.put("configValue", c.getConfigValue());
                    m.put("isSecret", c.getIsSecret());
                    m.put("remark", c.getRemark());
                    return m;
                })
                .toList();
    }

    @Override
    public boolean updateValue(String configKey, String configValue) {
        SystemConfig cfg = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, configKey));
        if (cfg == null) {
            return false;
        }
        cfg.setConfigValue(configValue);
        systemConfigMapper.updateById(cfg);
        return true;
    }
}
