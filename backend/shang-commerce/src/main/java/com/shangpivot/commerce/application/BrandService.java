package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.Brand;
import com.shangpivot.commerce.entity.Category;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.BrandMapper;
import com.shangpivot.commerce.mapper.CategoryMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.result.PageResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 品牌查询：按所属类目（含子孙）筛选；附带关联 SPU 数。
 * <p>筛选规则：brand.category_id ∈ 类目树，或该类目树下商品引用了该品牌。
 */
@Service
@RequiredArgsConstructor
public class BrandService {
    private final BrandMapper brandMapper;
    private final SpuMapper spuMapper;
    private final CategoryMapper categoryMapper;

    public PageResult<BrandListItem> page(int page, int size, String name, Long categoryId) {
        Set<Long> catIds = categoryId == null ? null : collectCategoryTreeIds(categoryId);
        Set<Long> brandIdsFromSpu = null;
        if (catIds != null) {
            brandIdsFromSpu = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                            .in(Spu::getCategoryId, catIds))
                    .stream()
                    .map(Spu::getBrandId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
        }

        LambdaQueryWrapper<Brand> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(name), Brand::getName, name);
        if (catIds != null) {
            final Set<Long> spuBrandIds = brandIdsFromSpu == null ? Set.of() : brandIdsFromSpu;
            q.and(w -> w.in(Brand::getCategoryId, catIds)
                    .or(!spuBrandIds.isEmpty(), ww -> ww.in(Brand::getId, spuBrandIds)));
        }
        q.orderByAsc(Brand::getSort).orderByAsc(Brand::getId);

        Page<Brand> p = brandMapper.selectPage(new Page<>(page, size), q);

        Map<Long, Long> spuCountByBrand = new HashMap<>();
        if (!p.getRecords().isEmpty()) {
            List<Long> ids = p.getRecords().stream().map(Brand::getId).toList();
            LambdaQueryWrapper<Spu> sq = new LambdaQueryWrapper<Spu>().in(Spu::getBrandId, ids);
            if (catIds != null) {
                sq.in(Spu::getCategoryId, catIds);
            }
            for (Spu s : spuMapper.selectList(sq)) {
                if (s.getBrandId() != null) {
                    spuCountByBrand.merge(s.getBrandId(), 1L, Long::sum);
                }
            }
        }

        List<BrandListItem> rows = new ArrayList<>(p.getRecords().size());
        for (Brand b : p.getRecords()) {
            BrandListItem item = new BrandListItem();
            item.setId(b.getId());
            item.setCategoryId(b.getCategoryId());
            item.setName(b.getName());
            item.setLogo(b.getLogo());
            item.setSort(b.getSort());
            item.setStatus(b.getStatus());
            item.setSpuCount(spuCountByBrand.getOrDefault(b.getId(), 0L).intValue());
            rows.add(item);
        }
        return PageResult.of(p.getTotal(), rows);
    }

    /** 含自身与全部子孙类目 ID。 */
    public Set<Long> collectCategoryTreeIds(Long rootId) {
        Set<Long> ids = new HashSet<>();
        ids.add(rootId);
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, List<Long>> children = new HashMap<>();
        for (Category c : all) {
            if (c.getParentId() != null && c.getParentId() > 0) {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        List<Long> queue = new ArrayList<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long cur = queue.remove(0);
            for (Long child : children.getOrDefault(cur, List.of())) {
                if (ids.add(child)) {
                    queue.add(child);
                }
            }
        }
        return ids;
    }

    @Data
    public static class BrandListItem {
        private Long id;
        private Long categoryId;
        private String name;
        private String logo;
        private Integer sort;
        private String status;
        private Integer spuCount;
    }
}
