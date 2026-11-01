package com.shangpivot.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.api.OrderQueryApi;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.identity.entity.Customer;
import com.shangpivot.identity.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台会员管理：列表、详情、冻结/解冻。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAdminService {

    private static final Set<String> STATUSES = Set.of("NORMAL", "FROZEN");

    private final CustomerMapper customerMapper;
    private final OrderQueryApi orderQueryApi;

    public PageResult<Map<String, Object>> page(int page, int size, String phone, String nickname, String status,
                                                String clientType) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(phone), Customer::getPhone, phone)
                .like(StringUtils.hasText(nickname), Customer::getNickname, nickname)
                .eq(StringUtils.hasText(status), Customer::getStatus, status)
                .orderByDesc(Customer::getId);
        if (StringUtils.hasText(clientType)) {
            Set<Long> ids = orderQueryApi.findCustomerIdsByClientType(clientType);
            if (ids.isEmpty()) {
                return PageResult.of(0, List.of());
            }
            q.in(Customer::getId, ids);
        }
        Page<Customer> p = customerMapper.selectPage(new Page<>(page, size), q);
        List<Long> ids = p.getRecords().stream().map(Customer::getId).toList();
        Map<Long, Long> orderCounts = orderQueryApi.countByCustomerIds(ids);
        List<Map<String, Object>> records = p.getRecords().stream().map(c -> {
            Map<String, Object> m = toBrief(c);
            m.put("orderCount", orderCounts.getOrDefault(c.getId(), 0L));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    public Map<String, Object> detail(Long id) {
        Customer c = require(id);
        Map<String, Object> m = toBrief(c);
        m.put("orderCount", orderQueryApi.countByCustomerId(id));
        return m;
    }

    @Transactional
    public void updateStatus(Long id, String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 须为 NORMAL 或 FROZEN");
        }
        Customer c = require(id);
        c.setStatus(status);
        customerMapper.updateById(c);
        log.info("Customer status updated id={} status={}", id, status);
    }

    private Customer require(Long id) {
        Customer c = customerMapper.selectById(id);
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会员不存在");
        }
        return c;
    }

    private Map<String, Object> toBrief(Customer c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("phone", CustomerAuthService.maskPhone(c.getPhone()));
        m.put("nickname", c.getNickname());
        m.put("avatar", c.getAvatar());
        m.put("status", c.getStatus());
        m.put("lastLoginAt", c.getLastLoginAt());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
