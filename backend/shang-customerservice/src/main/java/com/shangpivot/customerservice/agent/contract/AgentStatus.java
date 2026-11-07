package com.shangpivot.customerservice.agent.contract;

/** 子 Agent / 一次运行的结果状态（对齐 new1107 AgentResult 契约）。 */
public enum AgentStatus {
    /** 正常完成。 */
    OK,
    /** 缺少槽位，需要用户补充（如订单号/规格）。 */
    NEED_USER_INPUT,
    /** 写操作已生成计划，等待用户确认。 */
    NEED_CONFIRMATION,
    /** 转人工。 */
    HANDOFF,
    /** 失败（可降级或重试）。 */
    FAILED
}
