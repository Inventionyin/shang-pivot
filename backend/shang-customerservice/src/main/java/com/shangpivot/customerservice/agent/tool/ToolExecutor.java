package com.shangpivot.customerservice.agent.tool;

import java.util.Map;

/** 工具实现。实现类只允许走 commerce-api / identity-api / 本上下文服务。 */
public interface ToolExecutor {

    ToolDefinition definition();

    ToolResult execute(ToolContext context, Map<String, Object> args);
}
