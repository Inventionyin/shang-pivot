package com.shangpivot.customerservice.agent.tool.impl;

import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.tool.AbstractTool;
import com.shangpivot.customerservice.agent.tool.ToolContext;
import com.shangpivot.customerservice.agent.tool.ToolResult;
import com.shangpivot.customerservice.agent.tool.ToolRisk;
import com.shangpivot.customerservice.retrieval.KnowledgeSearchService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 知识库检索：只返回发布态文档切片，答案必须带引用。 */
@Component
public class KnowledgeSearchTool extends AbstractTool {

    private final KnowledgeSearchService knowledgeSearchService;

    public KnowledgeSearchTool(KnowledgeSearchService knowledgeSearchService) {
        super("searchKnowledge", "检索售后政策、FAQ、活动规则等发布态知识",
                ToolRisk.READ, Set.of(AgentName.KNOWLEDGE, AgentName.AFTER_SALE), Duration.ofSeconds(3), false,
                schema(List.of("query"), Map.of(
                        "query", property("string", "检索问题"),
                        "topK", property("integer", "返回条数，默认 2，最大 5"))));
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String query = stringArg(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.fail("INVALID_ARGS", "检索问题必填");
        }
        int topK = intArg(args, "topK", 2, 1, 5);
        List<KnowledgeSearchService.KnowledgeHit> hits = knowledgeSearchService.search(query, topK);
        return ToolResult.ok(hits, hits.isEmpty() ? "未命中知识" : "命中 " + hits.size() + " 条知识");
    }
}
