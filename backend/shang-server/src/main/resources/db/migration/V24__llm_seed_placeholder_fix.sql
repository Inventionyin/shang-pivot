-- V24：清空 V22 种子中的 LLM 网关占位符，让 .env / 环境变量生效
-- 背景：llm.base-url 种子值为脱敏占位符（非空），LlmSettingsResolver 的
--       「system_config 优先于环境变量」策略会把它当成真实配置，导致全新安装
--       即使填了 LLM_BASE_URL 也永远走降级。这里只清掉占位符本身，
--       已由管理端配置过真实地址的环境（如生产）不受影响。
UPDATE identity.system_config
SET config_value = '', updated_at = now()
WHERE config_key = 'llm.base-url'
  AND config_value = 'https://your-openai-compatible-gateway.example/v1';
