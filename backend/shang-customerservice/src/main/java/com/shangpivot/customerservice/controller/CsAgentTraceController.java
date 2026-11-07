package com.shangpivot.customerservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.customerservice.agent.trace.entity.AgentRun;
import com.shangpivot.customerservice.agent.trace.entity.AgentStep;
import com.shangpivot.customerservice.agent.trace.entity.LlmCall;
import com.shangpivot.customerservice.agent.trace.entity.RetrievalTrace;
import com.shangpivot.customerservice.agent.trace.entity.ToolCall;
import com.shangpivot.customerservice.agent.trace.mapper.AgentRunMapper;
import com.shangpivot.customerservice.agent.trace.mapper.AgentStepMapper;
import com.shangpivot.customerservice.agent.trace.mapper.LlmCallMapper;
import com.shangpivot.customerservice.agent.trace.mapper.RetrievalTraceMapper;
import com.shangpivot.customerservice.agent.trace.mapper.ToolCallMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理端 Agent 运行轨迹（/admin/customer-service/agent-runs）。 */
@Tag(name = "管理端-Agent运行轨迹")
@RestController
@RequestMapping("/admin/customer-service/agent-runs")
@RequiredArgsConstructor
public class CsAgentTraceController {

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final LlmCallMapper llmCallMapper;
    private final ToolCallMapper toolCallMapper;
    private final RetrievalTraceMapper retrievalMapper;

    @Operation(summary = "运行轨迹分页")
    @GetMapping
    public Result<PageResult<AgentRun>> page(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String agent,
                                             @RequestParam(required = false) String intent,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        LambdaQueryWrapper<AgentRun> query = new LambdaQueryWrapper<AgentRun>()
                .eq(StringUtils.hasText(status), AgentRun::getStatus, status)
                .eq(StringUtils.hasText(agent), AgentRun::getAgent, agent)
                .eq(StringUtils.hasText(intent), AgentRun::getIntent, intent)
                .orderByDesc(AgentRun::getStartedAt)
                .orderByDesc(AgentRun::getId);
        Page<AgentRun> result = runMapper.selectPage(new Page<>(page, Math.min(size, 50)), query);
        return Result.ok(PageResult.of(result.getTotal(), result.getRecords()));
    }

    @Operation(summary = "运行轨迹详情")
    @GetMapping("/{runId}")
    public Result<Map<String, Object>> detail(@PathVariable Long runId) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            return Result.ok(Map.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", run);
        data.put("steps", stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, runId).orderByAsc(AgentStep::getId)));
        data.put("llmCalls", llmCallMapper.selectList(new LambdaQueryWrapper<LlmCall>()
                .eq(LlmCall::getRunId, runId).orderByAsc(LlmCall::getId)));
        data.put("toolCalls", toolCallMapper.selectList(new LambdaQueryWrapper<ToolCall>()
                .eq(ToolCall::getRunId, runId).orderByAsc(ToolCall::getId)));
        data.put("retrievals", retrievalMapper.selectList(new LambdaQueryWrapper<RetrievalTrace>()
                .eq(RetrievalTrace::getRunId, runId).orderByAsc(RetrievalTrace::getId)));
        return Result.ok(data);
    }
}
