package com.shangpivot.customerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shangpivot.customerservice.entity.CsAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CsAuditLogMapper extends BaseMapper<CsAuditLog> {
}
