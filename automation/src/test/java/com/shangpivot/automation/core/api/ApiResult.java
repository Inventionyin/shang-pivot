package com.shangpivot.automation.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.assertj.core.api.Assertions;

/**
 * 统一响应体 {@code {code,msg,data,traceId}} 的测试侧视图。
 */
public record ApiResult(int status, JsonNode root) {

    public int code() {
        return root == null ? -1 : root.path("code").asInt(-1);
    }

    public String message() {
        return root == null ? "" : root.path("msg").asText("");
    }

    public JsonNode data() {
        if (root == null) {
            return MissingNode.getInstance();
        }
        return root.has("data") ? root.get("data") : MissingNode.getInstance();
    }

    public String traceId() {
        return root == null ? "" : root.path("traceId").asText("");
    }

    public boolean success() {
        return status >= 200 && status < 300 && code() == 0;
    }

    public String raw() {
        return root == null ? "" : root.toPrettyString();
    }

    public ApiResult assertSuccess() {
        Assertions.assertThat(status)
                .as("HTTP 状态；响应=%s", raw())
                .isBetween(200, 299);
        Assertions.assertThat(code())
                .as("业务 code；msg=%s；响应=%s", message(), raw())
                .isZero();
        return this;
    }

    public ApiResult assertCode(int expected) {
        Assertions.assertThat(code())
                .as("期望业务码 %d；实际 msg=%s；响应=%s", expected, message(), raw())
                .isEqualTo(expected);
        return this;
    }

    public ApiResult assertHttpStatus(int expected) {
        Assertions.assertThat(status).as("HTTP 状态；响应=%s", raw()).isEqualTo(expected);
        return this;
    }

    public ApiResult assertMessageContains(String text) {
        Assertions.assertThat(message()).as("业务提示；响应=%s", raw()).contains(text);
        return this;
    }
}
