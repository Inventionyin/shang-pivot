package com.shangpivot.customerservice.agent.tool;

import com.shangpivot.customerservice.agent.contract.AgentName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工具基类：统一 schema/参数读取，减少每个工具的样板代码。 */
public abstract class AbstractTool implements ToolExecutor {

    private final ToolDefinition definition;

    protected AbstractTool(String name, String description, ToolRisk risk, Set<AgentName> allowedAgents,
                           Duration timeout, boolean idempotent, Map<String, Object> inputSchema) {
        this.definition = new ToolDefinition(name, description, risk, allowedAgents, timeout, idempotent, inputSchema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    protected static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    protected static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    protected static String stringArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    protected static Long longArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static int intArg(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        Long value = longArg(args, key);
        if (value == null) {
            return defaultValue;
        }
        return (int) Math.max(min, Math.min(max, value));
    }
}
