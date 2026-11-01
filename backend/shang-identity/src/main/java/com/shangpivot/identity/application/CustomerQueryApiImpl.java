package com.shangpivot.identity.application;

import com.shangpivot.identity.api.CustomerQueryApi;
import com.shangpivot.identity.entity.Customer;
import com.shangpivot.identity.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerQueryApiImpl implements CustomerQueryApi {

    private final CustomerMapper customerMapper;

    @Override
    public boolean isActive(Long customerId) {
        if (customerId == null) {
            return false;
        }
        Customer c = customerMapper.selectById(customerId);
        return c != null && "NORMAL".equals(c.getStatus());
    }

    @Override
    public String maskedPhone(Long customerId) {
        if (customerId == null) {
            return null;
        }
        Customer c = customerMapper.selectById(customerId);
        return c == null ? null : CustomerAuthService.maskPhone(c.getPhone());
    }
}
