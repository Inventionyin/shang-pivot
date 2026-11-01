package com.shangpivot.customerservice.llm;

import lombok.Data;

/**
 * LLM 运行时配置（.env 提供默认，identity.system_config 的 LLM 分组覆盖；空串视为未配置回落 .env）。
 * 见 ADR-0005。
 */
@Data
public class LlmSettings {

    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature;
    private String systemPrompt;

    /** 思考强度：none 关闭思考流（网关支持时生效，见 ADR-0005 补充）；留空则不带该参数 */
    private String reasoningEffort;

    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel;
    private int embeddingDim;

    /** auto / lexical / vector */
    private String retrievalMode;

    public boolean chatReady() {
        return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    public boolean embeddingReady() {
        return notBlank(embeddingBaseUrl) && notBlank(embeddingApiKey) && notBlank(embeddingModel);
    }

    public boolean vectorRetrievalActive() {
        return embeddingReady() && ("vector".equals(retrievalMode) || "auto".equals(retrievalMode));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
