package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shangpivot.commerce.entity.Address;
import com.shangpivot.commerce.mapper.AddressMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.api.CustomerQueryApi;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {

    private static final int MAX_ADDRESS = 20;

    private final AddressMapper addressMapper;
    private final CustomerQueryApi customerQueryApi;

    public List<Address> list() {
        Long customerId = requireActive();
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                .eq(Address::getCustomerId, customerId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getId));
    }

    @Transactional
    public Long create(AddressReq req) {
        Long customerId = requireActive();
        Long count = addressMapper.selectCount(new LambdaQueryWrapper<Address>().eq(Address::getCustomerId, customerId));
        if (count >= MAX_ADDRESS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "每个会员最多 20 条地址");
        }
        Address address = fromReq(req);
        address.setCustomerId(customerId);
        if (Boolean.TRUE.equals(req.getIsDefault()) || count == 0) {
            clearDefault(customerId);
            address.setIsDefault(true);
        } else {
            address.setIsDefault(false);
        }
        addressMapper.insert(address);
        return address.getId();
    }

    @Transactional
    public void update(Long id, AddressReq req) {
        Address address = requireOwned(id);
        apply(address, req);
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefault(address.getCustomerId());
            address.setIsDefault(true);
        }
        addressMapper.updateById(address);
    }

    @Transactional
    public void delete(Long id) {
        Address address = requireOwned(id);
        addressMapper.deleteById(address.getId());
    }

    @Transactional
    public void setDefault(Long id) {
        Address address = requireOwned(id);
        clearDefault(address.getCustomerId());
        address.setIsDefault(true);
        addressMapper.updateById(address);
    }

    private void clearDefault(Long customerId) {
        addressMapper.update(null, new LambdaUpdateWrapper<Address>()
                .eq(Address::getCustomerId, customerId)
                .set(Address::getIsDefault, false));
    }

    private Address requireOwned(Long id) {
        Long customerId = requireActive();
        Address address = addressMapper.selectById(id);
        if (address == null || !customerId.equals(address.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return address;
    }

    private Long requireActive() {
        Long id = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(id)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        return id;
    }

    private Address fromReq(AddressReq req) {
        Address a = new Address();
        apply(a, req);
        return a;
    }

    private void apply(Address a, AddressReq req) {
        if (!StringUtils.hasText(req.getName()) || !StringUtils.hasText(req.getPhone())
                || !StringUtils.hasText(req.getProvince()) || !StringUtils.hasText(req.getCity())
                || !StringUtils.hasText(req.getDistrict()) || !StringUtils.hasText(req.getDetail())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "收货信息不完整");
        }
        a.setName(req.getName().trim());
        a.setPhone(req.getPhone().trim());
        a.setProvince(req.getProvince().trim());
        a.setCity(req.getCity().trim());
        a.setDistrict(req.getDistrict().trim());
        a.setDetail(req.getDetail().trim());
    }

    @Data
    public static class AddressReq {
        private String name;
        private String phone;
        private String province;
        private String city;
        private String district;
        private String detail;
        private Boolean isDefault;
    }
}
