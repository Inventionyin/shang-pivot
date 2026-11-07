package com.shangpivot.customerservice.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spring AI 适配层契约：reasoning/content 分轨与累计值 diff。 */
class SpringAiLlmClientTest {

    @Test
    void streamsReasoningAndContentFromOpenAiCompatibleSse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", SpringAiLlmClientTest::handle);
        server.start();
        try {
            LlmSettingsResolver resolver = mock(LlmSettingsResolver.class);
            LlmSettings settings = new LlmSettings();
            settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            settings.setApiKey("test-key");
            settings.setModel("test-model");
            settings.setTemperature(0.5);
            when(resolver.resolve()).thenReturn(settings);

            SpringAiLlmClient client = new SpringAiLlmClient(resolver, new ObjectMapper());
            StringBuilder reasoning = new StringBuilder();
            StringBuilder content = new StringBuilder();
            LlmClient.StreamResult result = client.streamChat("system",
                    List.of(Map.of("role", "user", "content", "你好")),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onReasoning(String delta) {
                            reasoning.append(delta);
                        }

                        @Override
                        public void onContent(String delta) {
                            content.append(delta);
                        }
                    });

            assertEquals("先想一下", reasoning.toString());
            assertEquals("你好！", content.toString());
            assertEquals(reasoning.toString(), result.reasoning());
            assertEquals(content.toString(), result.content());
            assertTrue(client.available());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blockingChatReturnsContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", SpringAiLlmClientTest::handle);
        server.start();
        try {
            LlmSettingsResolver resolver = mock(LlmSettingsResolver.class);
            LlmSettings settings = new LlmSettings();
            settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            settings.setApiKey("test-key");
            settings.setModel("test-model");
            when(resolver.resolve()).thenReturn(settings);

            SpringAiLlmClient client = new SpringAiLlmClient(resolver, new ObjectMapper());
            assertEquals("你好", client.chat("system", "user", 0.2, 100));
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!request.contains("\"stream\":true")) {
            byte[] json = """
                    {"id":"1","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (var out = exchange.getResponseBody()) {
                out.write(json);
            }
            return;
        }
        String body = """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"先想"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"reasoning_content":"一下"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
