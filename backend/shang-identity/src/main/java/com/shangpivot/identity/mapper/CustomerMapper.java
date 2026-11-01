package com.shangpivot.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shangpivot.identity.entity.Customer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
