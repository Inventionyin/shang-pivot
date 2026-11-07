package com.shangpivot.customerservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 网关丢弃 system role 时的传输回退：user 模式下 system 指令与 facts 必须进入最后一条 user。 */
class PromptTransportTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmSettingsResolver resolver = Mockito.mock(LlmSettingsResolver.class);
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBody.set(request);
            boolean stream = mapper.readTree(request).path("stream").asBoolean(false);
            String response = stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        LlmSettings settings = settings("user");
        when(resolver.resolve()).thenReturn(settings);
        client = new OpenAiCompatibleLlmClient(resolver, mapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private LlmSettings settings(String mode) {
        LlmSettings s = new LlmSettings();
        s.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        s.setApiKey("test-key");
        s.setModel("test-model");
        s.setPromptMode(mode);
        return s;
    }

    @Test
    void userModeStreamHasNoSystemRoleAndFactsAreLastUser() throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "第一问"));
        messages.add(Map.of("role", "assistant", "content", "第一答"));
        messages.add(Map.of("role", "user", "content", "第二问"));

        client.streamChat("暗号是蓝鲸七号", messages, null);

        JsonNode sent = mapper.readTree(requestBody.get()).path("messages");
        assertEquals(4, sent.size());
        for (JsonNode m : sent) {
            assertFalse("system".equals(m.path("role").asText()), "user 模式不应发送 system role");
        }
        assertEquals("user", sent.get(0).path("role").asText());
        assertEquals("第一问", sent.get(0).path("content").asText());
        assertEquals("assistant", sent.get(1).path("role").asText());
        assertEquals("第二问", sent.get(2).path("content").asText());
        assertEquals("user", sent.get(3).path("role").asText());
        String folded = sent.get(3).path("content").asText();
        assertTrue(folded.contains("暗号是蓝鲸七号"));
        assertTrue(folded.contains("只回答上一条用户消息"));
    }

    @Test
    void nativeModeStillSendsSystemRoleFirst() throws Exception {
        when(resolver.resolve()).thenReturn(settings("native"));

        client.streamChat("人设", List.of(Map.of("role", "user", "content", "你好")), null);

        JsonNode sent = mapper.readTree(requestBody.get()).path("messages");
        assertEquals("system", sent.get(0).path("role").asText());
        assertEquals("人设", sent.get(0).path("content").asText());
        assertEquals("user", sent.get(1).path("role").asText());
    }

    @Test
    void userModeChatFoldsTaskAndInputIntoOneUserMessage() throws Exception {
        client.chat("任务说明", "用户输入", 0.1, 100);

        JsonNode sent = mapper.readTree(requestBody.get()).path("messages");
        assertEquals(1, sent.size());
        assertEquals("user", sent.get(0).path("role").asText());
        String content = sent.get(0).path("content").asText();
        assertTrue(content.contains("任务说明"));
        assertTrue(content.contains("用户输入"));
        assertTrue(content.contains("只输出任务要求的内容"));
    }

    @Test
    void springAiMapperAlsoHonorsUserMode() {
        List<Message> folded = SpringAiLlmClient.toMessages(settings("user"), "SYS",
                List.of(Map.of("role", "user", "content", "问")));
        assertTrue(folded.stream().noneMatch(SystemMessage.class::isInstance));
        assertTrue(folded.get(folded.size() - 1) instanceof UserMessage);
        assertTrue(((UserMessage) folded.get(folded.size() - 1)).getText().contains("SYS"));

        List<Message> nativeMessages = SpringAiLlmClient.toMessages(settings("native"), "SYS",
                List.of(Map.of("role", "user", "content", "问")));
        assertTrue(nativeMessages.get(0) instanceof SystemMessage);
    }
}
