package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.AfterSale;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.mapper.AfterSaleMapper;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 售后单查询与审核（沙箱退款：通过后直接 COMPLETED）。
 * <p>仅 PENDING_AUDIT 可审；通过 → REFUNDING → COMPLETED，驳回 → REJECTED。
 * <p>关联表：after_sale、orders。
 */
@Service
@RequiredArgsConstructor
public class AfterSaleService {
    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;

    public PageResult<AfterSale> page(int page, int size, String status, String type, String afterSaleNo) {
        LambdaQueryWrapper<AfterSale> q = new LambdaQueryWrapper<>();
        q.eq(StringUtils.hasText(status), AfterSale::getStatus, status)
                .eq(StringUtils.hasText(type), AfterSale::getType, type)
                .like(StringUtils.hasText(afterSaleNo), AfterSale::getAfterSaleNo, afterSaleNo)
                .orderByDesc(AfterSale::getId);
        Page<AfterSale> p = afterSaleMapper.selectPage(new Page<>(page, size), q);
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    public Map<String, Object> detail(Long id) {
        AfterSale as = require(id);
        Order order = orderMapper.selectById(as.getOrderId());
        Map<String, Object> data = new HashMap<>();
        data.put("afterSale", as);
        data.put("order", order);
        return data;
    }

    /**
     * 审核售后：通过则模拟退款并完结，驳回则 REJECTED。
     */
    @Transactional
    public void audit(Long id, AuditReq req) {
        AfterSale as = require(id);
        if (!"PENDING_AUDIT".equals(as.getStatus())) {
            throw new BizException(ErrorCode.AFTER_SALE_STATUS_INVALID);
        }
        if (req.getApproved() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择通过或拒绝");
        }
        if (!Boolean.TRUE.equals(req.getApproved()) && !StringUtils.hasText(req.getRemark())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拒绝时请填写处理意见");
        }
        as.setAuditRemark(req.getRemark());
        if (Boolean.TRUE.equals(req.getApproved())) {
            as.setStatus("REFUNDING");
            afterSaleMapper.updateById(as);
            // sandbox refund
            as.setStatus("COMPLETED");
            afterSaleMapper.updateById(as);
        } else {
            as.setStatus("REJECTED");
            afterSaleMapper.updateById(as);
        }
    }

    /** C 端申请售后：待收货 / 已完成可申请。 */
    @Transactional
    public Long apply(ApplyReq req) {
        return apply(SecurityContexts.requireUserId(), req.getOrderId(), req.getType(), req.getReason(),
                req.getEvidenceKeys(), req.getRefundAmount());
    }

    /** 跨上下文（Agent 工具）入口：customerId 显式传入，仍做归属/状态校验。 */
    @Transactional
    public Long apply(Long customerId, Long orderId, String type, String reason,
                      java.util.List<String> evidenceKeys, java.math.BigDecimal refundAmount) {
        if (orderId == null || !StringUtils.hasText(type) || !StringUtils.hasText(reason)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单、类型、原因必填");
        }
        if (!Set.of("REFUND", "RETURN_REFUND", "EXCHANGE").contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 无效");
        }
        if (reason.trim().length() > 512) {
            throw new BizException(ErrorCode.BAD_REQUEST, "售后原因不能超过 512 个字符");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!Set.of("PENDING_RECEIVE", "COMPLETED").contains(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_INVALID, "当前订单状态不可申请售后");
        }
        Long open = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getOrderId, order.getId())
                .in(AfterSale::getStatus, "PENDING_AUDIT", "REFUNDING"));
        if (open != null && open > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该订单已有进行中的售后");
        }
        AfterSale as = new AfterSale();
        as.setAfterSaleNo("AS" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", (int) (Math.random() * 10000)));
        as.setOrderId(order.getId());
        as.setCustomerId(customerId);
        as.setType(type);
        as.setReason(reason.trim());
        as.setEvidencePics(evidenceKeys);
        as.setStatus("PENDING_AUDIT");
        as.setRefundAmount(refundAmount != null ? refundAmount : order.getPayAmount());
        afterSaleMapper.insert(as);
        return as.getId();
    }

    public PageResult<AfterSale> myPage(int page, int size, String status) {
        Long customerId = SecurityContexts.requireUserId();
        LambdaQueryWrapper<AfterSale> q = new LambdaQueryWrapper<>();
        q.eq(AfterSale::getCustomerId, customerId)
                .eq(StringUtils.hasText(status), AfterSale::getStatus, status)
                .orderByDesc(AfterSale::getId);
        Page<AfterSale> p = afterSaleMapper.selectPage(new Page<>(page, size), q);
        return PageResult.of(p.getTotal(), p.getRecords());
    }

    public Map<String, Object> myDetail(Long id) {
        Long customerId = SecurityContexts.requireUserId();
        AfterSale as = require(id);
        if (!customerId.equals(as.getCustomerId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "售后单不存在");
        }
        Order order = orderMapper.selectById(as.getOrderId());
        Map<String, Object> data = new HashMap<>();
        data.put("afterSale", as);
        data.put("order", order);
        return data;
    }

    private AfterSale require(Long id) {
        AfterSale as = afterSaleMapper.selectById(id);
        if (as == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "售后单不存在");
        }
        return as;
    }

    @Data
    @Schema(description = "售后审核请求")
    public static class AuditReq {
        @Schema(description = "是否通过：true 通过，false 驳回", example = "true")
        private Boolean approved;
        @Schema(description = "审核备注", example = "同意退款")
        private String remark;
    }

    @Data
    @Schema(description = "C 端申请售后")
    public static class ApplyReq {
        private Long orderId;
        private String type;
        private String reason;
        private java.util.List<String> evidenceKeys;
        private java.math.BigDecimal refundAmount;
    }
}
