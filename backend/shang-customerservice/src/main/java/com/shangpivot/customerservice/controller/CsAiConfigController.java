package com.shangpivot.customerservice.controller;

import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import com.shangpivot.customerservice.service.AiConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AI 配置（/admin/customer-service/ai-config，读写 system_config LLM 分组）。 */
@Tag(name = "管理端-AI 配置")
@RestController
@RequestMapping("/admin/customer-service/ai-config")
@RequiredArgsConstructor
public class CsAiConfigController {

    private final AiConfigService aiConfigService;

    @Operation(summary = "读取 AI 配置（密文打码）")
    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.ok(aiConfigService.get());
    }

    @Operation(summary = "批量更新 AI 配置")
    @PutMapping
    @OpLog(module = "cs-ai-config", action = "update")
    public Result<Void> update(@RequestBody Map<String, String> values) {
        aiConfigService.update(values);
        return Result.ok();
    }

    @Operation(summary = "连通性测试")
    @PostMapping("/test")
    public Result<Map<String, Object>> test() {
        return Result.ok(aiConfigService.test());
    }
}
