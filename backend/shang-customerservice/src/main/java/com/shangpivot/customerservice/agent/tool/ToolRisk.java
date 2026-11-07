package com.shangpivot.customerservice.agent.tool;

/** 工具风险级别：读、低风险可逆写、高风险写。 */
public enum ToolRisk {
    READ,
    WRITE_LOW,
    WRITE_HIGH;

    public boolean write() {
        return this != READ;
    }
}
