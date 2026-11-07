package com.shangpivot.customerservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 适配层（AGENT_LLM_CLIENT=spring-ai 时启用）。
 * 与 OpenAiCompatibleLlmClient 同契约：流式 reasoning/content 分轨、token usage、降级可回退。
 * 注意：Spring AI 每个 chunk 的 reasoningContent 是累计值，这里做 diff 后回调增量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.llm-client", havingValue = "spring-ai")
public class SpringAiLlmClient implements LlmClient {

    private final LlmSettingsResolver settingsResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile CachedModel cached;

    private record CachedModel(String key, OpenAiChatModel model) {
    }

    @Override
    public boolean available() {
        return settingsResolver.resolve().chatReady();
    }

    @Override
    public StreamResult streamChat(String systemPrompt, List<Map<String, String>> messages, StreamListener listener) {
        LlmSettings settings = settingsResolver.resolve();
        if (!settings.chatReady()) {
            throw new LlmException("LLM 未配置（llm.base-url/api-key/model）");
        }
        OpenAiChatModel model = model(settings);
        Prompt prompt = new Prompt(toMessages(settings, systemPrompt, messages));
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        String lastReasoning = "";
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            for (ChatResponse response : model.stream(prompt).toIterable()) {
                if (response == null || response.getResult() == null) {
                    continue;
                }
                AssistantMessage output = response.getResult().getOutput();
                if (output.getText() != null && !output.getText().isEmpty()) {
                    content.append(output.getText());
                    if (listener != null) {
                        listener.onContent(output.getText());
                    }
                }
                Object reasoningValue = output.getMetadata().get("reasoningContent");
                if (reasoningValue instanceof String full && full.length() > lastReasoning.length()) {
                    String delta = full.substring(lastReasoning.length());
                    lastReasoning = full;
                    reasoning.append(delta);
                    if (listener != null) {
                        listener.onReasoning(delta);
                    }
                }
                Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) {
                        promptTokens = usage.getPromptTokens();
                    }
                    if (usage.getCompletionTokens() != null) {
                        completionTokens = usage.getCompletionTokens();
                    }
                }
            }
        } catch (Exception e) {
            throw new LlmException("Spring AI 流式请求失败: " + e.getMessage(), e);
        }
        return new StreamResult(reasoning.toString(), content.toString(), promptTokens, completionTokens);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        LlmSettings settings = settingsResolver.resolve();
        if (!settings.chatReady()) {
            throw new LlmException("LLM 未配置");
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(settings.getModel())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        Prompt prompt = new Prompt(toMessages(settings, systemPrompt,
                List.of(Map.of("role", "user", "content", userPrompt))), options);
        try {
            ChatResponse response = model(settings).call(prompt);
            return response.getResult() == null || response.getResult().getOutput() == null
                    ? "" : response.getResult().getOutput().getText();
        } catch (Exception e) {
            throw new LlmException("Spring AI 请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listModels() {
        LlmSettings settings = settingsResolver.resolve();
        if (!settings.chatReady()) {
            throw new LlmException("LLM 未配置");
        }
        String url = trimSlash(settings.getBaseUrl()) + "/models";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + settings.getApiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("模型列表返回 " + response.statusCode());
            }
            JsonNode node = objectMapper.readTree(response.body());
            List<String> ids = new ArrayList<>();
            node.path("data").forEach(item -> ids.add(item.path("id").asText()));
            return ids;
        } catch (IOException e) {
            throw new LlmException("模型列表请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("模型列表请求被中断", e);
        }
    }

    private OpenAiChatModel model(LlmSettings settings) {
        String key = String.join("|", settings.getBaseUrl(), settings.getApiKey(), settings.getModel(),
                String.valueOf(settings.getTemperature()), String.valueOf(settings.getReasoningEffort()));
        CachedModel current = cached;
        if (current != null && key.equals(current.key())) {
            return current.model();
        }
        synchronized (this) {
            if (cached != null && key.equals(cached.key())) {
                return cached.model();
            }
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                    .baseUrl(settings.getBaseUrl())
                    .apiKey(settings.getApiKey())
                    .model(settings.getModel())
                    .temperature(settings.getTemperature());
            if (settings.getReasoningEffort() != null && !settings.getReasoningEffort().isBlank()) {
                builder.reasoningEffort(settings.getReasoningEffort());
            }
            OpenAiChatModel model = OpenAiChatModel.builder().options(builder.build()).build();
            cached = new CachedModel(key, model);
            return model;
        }
    }

    static List<Message> toMessages(LlmSettings settings, String systemPrompt,
                                    List<Map<String, String>> messages) {
        List<Message> result = new ArrayList<>();
        if (!LlmPromptTransport.userMode(settings)) {
            result.add(new SystemMessage(systemPrompt == null ? "" : systemPrompt));
        }
        if (messages != null) {
            for (Map<String, String> message : messages) {
                String role = message.getOrDefault("role", "user");
                String content = message.getOrDefault("content", "");
                result.add("assistant".equals(role) ? new AssistantMessage(content) : new UserMessage(content));
            }
        }
        if (LlmPromptTransport.userMode(settings)) {
            result.add(new UserMessage(LlmPromptTransport.foldSystem(systemPrompt)));
        }
        return result;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
