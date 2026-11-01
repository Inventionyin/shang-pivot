package com.shangpivot.common.audit;

/**
 * 操作审计落库端口。由 identity 模块提供实现，业务模块只依赖本接口与 {@link OpLog}。
 */
public interface OperationLogRecorder {

    /** 从当前请求上下文采集操作人 / IP / UA 后异步落库。 */
    void record(String module, String action, Object params, String result);

    /** 显式指定操作人信息（登录等尚无 Security 上下文时）。 */
    void record(String module, String action, Object params, String result,
                Long userId, String username, String ip, String ua);
}
