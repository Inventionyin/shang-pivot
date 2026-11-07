package com.shangpivot.customerservice.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.1 Agent runtime spike（Day 1-2 门禁）：
 * 1) Spring AI 2.0.2 能在 Boot 4.1.1 下构造并流式解析 reasoning_content；
 * 2) LangGraph4j 1.9.8 能建图、执行、使用 checkpoint。
 *
 * 两个用例都不访问外网：OpenAI 兼容端点由 JDK HttpServer 模拟。
 */
class AgentRuntimeSpikeTest {

    @Test
    void springAiStreamsReasoningAndContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", AgentRuntimeSpikeTest::handleChatCompletions);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey("spike-key")
                    .model("spike-model")
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();

            List<ChatResponse> responses = model.stream(new Prompt("你好"))
                    .collectList()
                    .block(Duration.ofSeconds(10));
            assertNotNull(responses, "stream must produce responses");

            StringBuilder content = new StringBuilder();
            String reasoning = "";
            for (ChatResponse response : responses) {
                AssistantMessage output = response.getResult().getOutput();
                if (output.getText() != null) {
                    content.append(output.getText());
                }
                Object value = output.getMetadata().get("reasoningContent");
                if (value instanceof String text && !text.isBlank()) {
                    reasoning = text;
                }
            }
            assertTrue(content.toString().contains("你好"), "content should be streamed: " + content);
            assertTrue(reasoning.contains("先想一下"), "reasoning should be parsed: " + reasoning);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void langGraph4jRunsGraphWithCheckpoint() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        AsyncNodeAction<AgentState> start = state -> CompletableFuture.completedFuture(Map.of("value", "hello"));
        AsyncNodeAction<AgentState> next = state -> CompletableFuture.completedFuture(
                Map.of("value", state.value("value").orElse("") + "-world"));
        graph.addNode("start", start);
        graph.addNode("next", next);
        graph.addEdge(StateGraph.START, "start");
        graph.addEdge("start", "next");
        graph.addEdge("next", StateGraph.END);

        CompiledGraph<AgentState> compiled = graph.compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        Optional<AgentState> result = compiled.invoke(Map.of("value", "init"));

        assertTrue(result.isPresent(), "graph should return a final state");
        assertEquals("hello-world", result.get().value("value").orElse(""));
    }

    @Test
    void langGraph4jSupportsConditionalEdges() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        AsyncNodeAction<AgentState> route = state -> CompletableFuture.completedFuture(Map.of("status", "OK"));
        AsyncNodeAction<AgentState> answer = state -> CompletableFuture.completedFuture(Map.of("value", "answered"));
        AsyncNodeAction<AgentState> reject = state -> CompletableFuture.completedFuture(Map.of("value", "rejected"));
        graph.addNode("route", route);
        graph.addNode("answer", answer);
        graph.addNode("reject", reject);
        graph.addEdge(StateGraph.START, "route");
        graph.addConditionalEdges("route",
                AsyncEdgeAction.edge_async(state -> "OK".equals(state.value("status").orElse("")) ? "answer" : "reject"),
                Map.of("answer", "answer", "reject", "reject"));
        graph.addEdge("answer", StateGraph.END);
        graph.addEdge("reject", StateGraph.END);

        CompiledGraph<AgentState> compiled = graph.compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        Optional<AgentState> result = compiled.invoke(Map.of());
        assertTrue(result.isPresent(), "conditional graph should return a final state");
        assertEquals("answered", result.get().value("value").orElse(""));
    }

    private static void handleChatCompletions(HttpExchange exchange) throws IOException {
        // 读掉请求体，避免连接复用问题；内容不影响本 spike。
        exchange.getRequestBody().readAllBytes();
        String body = """
                data: {"id":"spike-1","object":"chat.completion.chunk","created":1,"model":"spike-model","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"先想"},"finish_reason":null}]}

                data: {"id":"spike-1","object":"chat.completion.chunk","created":1,"model":"spike-model","choices":[{"index":0,"delta":{"reasoning_content":"一下"},"finish_reason":null}]}

                data: {"id":"spike-1","object":"chat.completion.chunk","created":1,"model":"spike-model","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

                data: {"id":"spike-1","object":"chat.completion.chunk","created":1,"model":"spike-model","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
