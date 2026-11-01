package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.Review;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.ReviewMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.identity.api.CustomerQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewAdminService {

    private static final Set<String> STATUSES = Set.of("VISIBLE", "HIDDEN");

    private final ReviewMapper reviewMapper;
    private final SpuMapper spuMapper;
    private final CustomerQueryApi customerQueryApi;

    public PageResult<Map<String, Object>> page(int page, int size, Integer rating, Integer ratingMax,
                                                String status, String keyword) {
        LambdaQueryWrapper<Review> q = new LambdaQueryWrapper<>();
        q.eq(rating != null && ratingMax == null, Review::getRating, rating)
                .le(ratingMax != null, Review::getRating, ratingMax)
                .ge(ratingMax != null, Review::getRating, 1)
                .eq(StringUtils.hasText(status), Review::getStatus, status)
                .like(StringUtils.hasText(keyword), Review::getContent, keyword)
                .orderByDesc(Review::getId);
        Page<Review> p = reviewMapper.selectPage(new Page<>(page, size), q);
        Set<Long> spuIds = p.getRecords().stream().map(Review::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Spu> spuMap = spuIds.isEmpty() ? Map.of() :
                spuMapper.selectBatchIds(spuIds).stream().collect(Collectors.toMap(Spu::getId, s -> s, (a, b) -> a));
        List<Map<String, Object>> records = p.getRecords().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("orderItemId", r.getOrderItemId());
            m.put("customerId", r.getCustomerId());
            m.put("spuId", r.getSpuId());
            m.put("rating", r.getRating());
            m.put("content", r.getContent());
            m.put("pics", r.getPics());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            Spu spu = spuMap.get(r.getSpuId());
            m.put("spuName", spu == null ? null : spu.getName());
            m.put("customerPhone", customerQueryApi.maskedPhone(r.getCustomerId()));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    @Transactional
    public void updateStatus(Long id, String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 须为 VISIBLE 或 HIDDEN");
        }
        Review review = reviewMapper.selectById(id);
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        review.setStatus(status);
        reviewMapper.updateById(review);
    }
}
