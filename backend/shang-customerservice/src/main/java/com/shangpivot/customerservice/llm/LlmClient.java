package com.shangpivot.customerservice.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 客户端（OpenAI chat-completions 兼容）。实现需支持：
 * - SSE 流式：区分 reasoning_content（思考流）与 content（正文）；
 * - 阻塞调用：意图识别等一次性补全。
 * 见 ADR-0005。
 */
public interface LlmClient {

    /** 配置是否可发起对话（未配置 key → false，调用方走降级）。 */
    boolean available();

    /**
     * 流式对话。回调在实现线程上同步触发；任何异常以 onError 终止且不再回调。
     *
     * @param systemPrompt 系统提示词
     * @param messages     对话历史（role → content，不含 system）
     * @param listener     流式回调
     * @return 完整结果（reasoning + content 拼接）
     */
    StreamResult streamChat(String systemPrompt, List<Map<String, String>> messages, StreamListener listener);

    /** 阻塞式补全（小任务：意图识别/槽位提取）。失败抛 LlmException。 */
    String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens);

    /** 探活：拉模型列表；异常抛 LlmException。 */
    List<String> listModels();

    /** 流式回调。 */
    interface StreamListener {
        /** 思考流增量（可能为空）。 */
        default void onReasoning(String delta) {
        }

        /** 正文增量。 */
        default void onContent(String delta) {
        }
    }

    record StreamResult(String reasoning, String content, long promptTokens, long completionTokens) {

        public StreamResult(String reasoning, String content) {
            this(reasoning, content, 0, 0);
        }
    }

    class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
