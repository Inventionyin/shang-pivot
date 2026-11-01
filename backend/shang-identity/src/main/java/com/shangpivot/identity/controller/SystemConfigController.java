package com.shangpivot.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.identity.entity.SystemConfig;
import com.shangpivot.identity.mapper.SystemConfigMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/system-configs")
@RequiredArgsConstructor
@Tag(name = "系统配置", description = "系统参数配置的查询与更新")
public class SystemConfigController {
    private final SystemConfigMapper systemConfigMapper;

    @GetMapping
    @Operation(summary = "查询系统配置", description = "按分类查询系统配置列表，敏感值自动脱敏")
    public Result<List<Map<String, Object>>> list(
            @Parameter(description = "配置分类", example = "oss") @RequestParam(required = false) String category) {
        List<SystemConfig> list = systemConfigMapper.selectList(new LambdaQueryWrapper<SystemConfig>()
                .eq(StringUtils.hasText(category), SystemConfig::getCategory, category)
                .orderByAsc(SystemConfig::getId));
        return Result.ok(list.stream().map(this::mask).toList());
    }

    @PutMapping("/{id}")
    @OpLog(module = "config", action = "update")
    @Operation(summary = "更新系统配置", description = "修改指定配置项的值与备注")
    public Result<Void> update(
            @Parameter(description = "配置ID", example = "1") @PathVariable Long id,
            @RequestBody SystemConfig patch) {
        SystemConfig cfg = systemConfigMapper.selectById(id);
        if (cfg == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        cfg.setConfigValue(patch.getConfigValue());
        cfg.setRemark(patch.getRemark());
        systemConfigMapper.updateById(cfg);
        return Result.ok();
    }

    private Map<String, Object> mask(SystemConfig c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("configKey", c.getConfigKey());
        m.put("category", c.getCategory());
        m.put("isSecret", c.getIsSecret());
        m.put("remark", c.getRemark());
        if (Boolean.TRUE.equals(c.getIsSecret()) && StringUtils.hasText(c.getConfigValue())) {
            m.put("configValue", "******");
        } else {
            m.put("configValue", c.getConfigValue());
        }
        return m;
    }
}
