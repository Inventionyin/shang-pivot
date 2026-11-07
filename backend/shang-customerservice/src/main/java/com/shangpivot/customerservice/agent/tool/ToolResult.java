package com.shangpivot.customerservice.agent.tool;

import java.util.Map;

/**
 * 工具执行结果。data 会被拼入模型上下文；summary 用于 trace 与前端确认卡。
 */
public record ToolResult(
        boolean success,
        Object data,
        String summary,
        String errorCode,
        String errorMessage) {

    public static ToolResult ok(Object data, String summary) {
        return new ToolResult(true, data, summary, null, null);
    }

    public static ToolResult fail(String errorCode, String errorMessage) {
        return new ToolResult(false, Map.of(), errorMessage, errorCode, errorMessage);
    }
}
