package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSnapshotDTO;
import com.shangpivot.commerce.entity.Category;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.CategoryMapper;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品跨上下文查询实现：检索/卡片口径复用 {@link CatalogReadService#toCard}。
 */
@Service
@RequiredArgsConstructor
public class ProductQueryApiImpl implements ProductQueryApi {

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;

    @Override
    public List<ProductCardDTO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword) || limit <= 0) {
            return Collections.emptyList();
        }
        List<Spu> spus = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, "ON")
                .and(w -> w.like(Spu::getName, keyword).or().like(Spu::getSubtitle, keyword))
                .orderByDesc(Spu::getSoldCount)
                .last("LIMIT " + Math.min(limit, 20)));
        return toCards(spus);
    }

    @Override
    public List<ProductCardDTO> getBySpuIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Spu> spus = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, "ON")
                .in(Spu::getId, spuIds));
        return toCards(spus);
    }

    @Override
    public List<ProductSnapshotDTO> snapshotPage(long lastSpuId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Spu> spus = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, "ON")
                .gt(Spu::getId, lastSpuId)
                .orderByAsc(Spu::getId)
                .last("LIMIT " + Math.min(limit, 200)));
        if (spus.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> categoryNames = categoryNamesOf(spus);
        Map<Long, List<Sku>> skusBySpu = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getSpuId, spus.stream().map(Spu::getId).toList())
                        .eq(Sku::getStatus, "ENABLED"))
                .stream()
                .collect(Collectors.groupingBy(Sku::getSpuId));
        return spus.stream().map(spu -> {
            List<Sku> skus = skusBySpu.getOrDefault(spu.getId(), List.of());
            BigDecimal min = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
            BigDecimal max = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
            return new ProductSnapshotDTO(
                    spu.getId(),
                    spu.getName(),
                    spu.getSubtitle(),
                    categoryNames.get(spu.getCategoryId()),
                    min,
                    max,
                    spu.getSoldCount() == null ? 0L : spu.getSoldCount()
            );
        }).toList();
    }

    @Override
    public long countOnSale() {
        return spuMapper.selectCount(new LambdaQueryWrapper<Spu>().eq(Spu::getStatus, "ON"));
    }

    private List<ProductCardDTO> toCards(List<Spu> spus) {
        if (spus.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> categoryNames = categoryNamesOf(spus);
        Map<Long, List<Sku>> skusBySpu = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getSpuId, spus.stream().map(Spu::getId).toList())
                        .eq(Sku::getStatus, "ENABLED"))
                .stream()
                .collect(Collectors.groupingBy(Sku::getSpuId));
        return spus.stream().map(spu -> {
            List<Sku> skus = skusBySpu.getOrDefault(spu.getId(), List.of());
            BigDecimal min = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(min);
            return new ProductCardDTO(
                    spu.getId(),
                    spu.getName(),
                    spu.getSubtitle(),
                    spu.getPics() == null || spu.getPics().isEmpty() ? null : spu.getPics().get(0),
                    min,
                    max.compareTo(min) == 0 ? null : max,
                    spu.getSoldCount() == null ? 0L : spu.getSoldCount(),
                    categoryNames.get(spu.getCategoryId())
            );
        }).toList();
    }

    private Map<Long, String> categoryNamesOf(List<Spu> spus) {
        List<Long> categoryIds = spus.stream().map(Spu::getCategoryId).filter(Objects::nonNull).distinct().toList();
        if (categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }
}
