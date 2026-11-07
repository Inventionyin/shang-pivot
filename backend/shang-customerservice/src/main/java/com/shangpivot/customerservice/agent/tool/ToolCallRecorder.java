package com.shangpivot.customerservice.agent.tool;

/** 工具调用埋点接口；实现失败不能影响主流程。 */
public interface ToolCallRecorder {

    void record(ToolCallRecord record);
}
