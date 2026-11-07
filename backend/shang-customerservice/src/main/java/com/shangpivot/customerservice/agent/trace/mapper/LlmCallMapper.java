package com.shangpivot.customerservice.agent.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shangpivot.customerservice.agent.trace.entity.LlmCall;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmCallMapper extends BaseMapper<LlmCall> {
}
