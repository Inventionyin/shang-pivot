package com.shangpivot.customerservice.service;

import com.shangpivot.identity.api.SystemConfigAdminApi;
import com.shangpivot.customerservice.llm.LlmClient;
import com.shangpivot.customerservice.llm.LlmSettings;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 配置（PRD §8.8）：读写 system_config 的 LLM 分组（经 identity-api，红线）+ 连通性测试。
 */
@Service
@RequiredArgsConstructor
public class AiConfigService {

    /** 允许通过接口修改的键白名单。 */
    private static final List<String> EDITABLE_KEYS = List.of(
            "llm.base-url", "llm.api-key", "llm.model", "llm.temperature", "llm.system-prompt",
            "llm.embedding.base-url", "llm.embedding.api-key", "llm.embedding.model", "llm.embedding.dim",
            "retrieval.mode");

    private final SystemConfigAdminApi systemConfigAdminApi;
    private final LlmSettingsResolver settingsResolver;
    private final LlmClient llmClient;

    public Map<String, Object> get() {
        Map<String, Object> r = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : systemConfigAdminApi.listByCategory("LLM")) {
            Map<String, Object> m = new HashMap<>(row);
            if (Boolean.TRUE.equals(m.get("isSecret"))) {
                Object v = m.get("configValue");
                m.put("configValue", v == null || v.toString().isBlank() ? "" : "******");
                m.put("hasValue", v != null && !v.toString().isBlank());
            }
            rows.add(m);
        }
        r.put("items", rows);
        LlmSettings s = settingsResolver.resolve();
        r.put("chatReady", s.chatReady());
        r.put("embeddingReady", s.embeddingReady());
        r.put("retrievalMode", s.getRetrievalMode());
        return r;
    }

    /** 批量更新（仅白名单键；secret 传 "******" 视为未修改）。 */
    public void update(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            if (!EDITABLE_KEYS.contains(key)) {
                throw new com.shangpivot.common.exception.BizException(
                        com.shangpivot.common.result.ErrorCode.BAD_REQUEST.getCode(), "不允许修改配置项: " + key);
            }
            String value = e.getValue();
            if ("******".equals(value)) {
                continue;
            }
            if (key.equals("retrieval.mode") && !List.of("auto", "lexical", "vector").contains(value)) {
                throw new com.shangpivot.common.exception.BizException(
                        com.shangpivot.common.result.ErrorCode.BAD_REQUEST.getCode(), "检索模式仅支持 auto/lexical/vector");
            }
            if (key.equals("llm.temperature")) {
                try {
                    double t = Double.parseDouble(value);
                    if (t < 0 || t > 1) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    throw new com.shangpivot.common.exception.BizException(
                            com.shangpivot.common.result.ErrorCode.BAD_REQUEST.getCode(), "Temperature 需在 0~1 之间");
                }
            }
            systemConfigAdminApi.updateValue(key, value == null ? "" : value);
        }
    }

    /** 连通性测试：模型列表探活。 */
    public Map<String, Object> test() {
        Map<String, Object> r = new HashMap<>();
        try {
            List<String> models = llmClient.listModels();
            r.put("ok", true);
            r.put("modelCount", models.size());
            r.put("containsCurrentModel", models.contains(settingsResolver.resolve().getModel()));
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getMessage());
        }
        return r;
    }
}
