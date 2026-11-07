package com.shangpivot.customerservice.agent.contract;

/** V3.1 子 Agent 角色。Handoff 是策略节点，不单独调用 LLM。 */
public enum AgentName {
    SHOPPING_GUIDE,
    ORDER,
    AFTER_SALE,
    KNOWLEDGE,
    HANDOFF
}
