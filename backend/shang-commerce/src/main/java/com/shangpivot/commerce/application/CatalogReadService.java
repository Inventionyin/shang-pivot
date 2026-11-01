package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.*;
import com.shangpivot.commerce.mapper.*;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.identity.api.SystemConfigApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** C 端只读目录：首页 / 分类 / 商品 / 搜索。 */
@Service
@RequiredArgsConstructor
public class CatalogReadService {

    private final CategoryMapper categoryMapper;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final StockMapper stockMapper;
    private final ReviewMapper reviewMapper;
    private final SiteBrandService siteBrandService;
    private final HomeSlotService homeSlotService;
    private final SystemConfigApi systemConfigApi;

    public Map<String, Object> home(int feedSize) {
        Map<String, Object> m = new HashMap<>();
        m.put("brand", siteBrandService.get());
        m.put("slots", homeSlotService.listForStorefront());
        String sort = systemConfigApi.getValue("home.feed-sort", "sold");
        m.put("feed", productPage(null, 1, feedSize, "sold".equals(sort) ? "sold" : "new").getRecords());
        return m;
    }

    public List<Map<String, Object>> categories() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, "ENABLED")
                .orderByAsc(Category::getSort));
        Map<Long, List<Category>> byParent = all.stream().collect(Collectors.groupingBy(Category::getParentId));
        return buildTree(0L, byParent);
    }

    public PageResult<Map<String, Object>> productPage(Long categoryId, int page, int size, String sort) {
        LambdaQueryWrapper<Spu> q = new LambdaQueryWrapper<>();
        q.eq(Spu::getStatus, "ON")
                .eq(categoryId != null, Spu::getCategoryId, categoryId);
        applySort(q, sort);
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size), q);
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toCard).toList());
    }

    public PageResult<Map<String, Object>> search(String keyword, int page, int size) {
        if (!StringUtils.hasText(keyword)) {
            return PageResult.of(0, List.of());
        }
        LambdaQueryWrapper<Spu> q = new LambdaQueryWrapper<>();
        q.eq(Spu::getStatus, "ON")
                .and(w -> w.like(Spu::getName, keyword).or().like(Spu::getSubtitle, keyword))
                .orderByDesc(Spu::getSoldCount);
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size), q);
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toCard).toList());
    }

    public Map<String, Object> detail(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || !"ON".equals(spu.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getStatus, "ENABLED"));
        List<Map<String, Object>> skuVos = skus.stream().map(sku -> {
            Map<String, Object> m = new HashMap<>();
            m.put("skuId", sku.getId());
            m.put("title", sku.getTitle());
            m.put("price", sku.getPrice());
            m.put("specs", sku.getSpecs());
            m.put("pic", sku.getPic());
            m.put("availableStock", available(sku.getId()));
            return m;
        }).toList();
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getSpuId, spuId)
                .eq(Review::getStatus, "VISIBLE")
                .orderByDesc(Review::getId)
                .last("LIMIT 5"));
        Map<String, Object> m = new HashMap<>();
        m.put("spu", spu);
        m.put("skus", skuVos);
        m.put("reviewsPreview", reviews);
        m.put("soldCount", spu.getSoldCount() == null ? 0 : spu.getSoldCount());
        return m;
    }

    private void applySort(LambdaQueryWrapper<Spu> q, String sort) {
        if ("sold".equals(sort)) {
            q.orderByDesc(Spu::getSoldCount);
        } else if ("new".equals(sort) || "comp".equals(sort)) {
            q.orderByDesc(Spu::getId);
        } else if ("price_asc".equals(sort) || "price_desc".equals(sort)) {
            // 价格排序：按该 SPU 下启用 SKU 的最低价排序。
            // 用相关子查询聚合，排序留在 SQL 里 —— 分页与 COUNT 才正确
            // （此前这里直接回落 id desc，参数被接受但排序不生效）。
            String dir = "price_asc".equals(sort) ? "ASC" : "DESC";
            q.last("ORDER BY (SELECT MIN(s.price) FROM commerce.sku s"
                    + " WHERE s.spu_id = commerce.spu.id AND s.status = 'ENABLED' AND s.deleted = FALSE)"
                    + " " + dir + " NULLS LAST, commerce.spu.id ASC");
        } else {
            q.orderByDesc(Spu::getSoldCount);
        }
    }

    public Map<String, Object> toCard(Spu spu) {
        Map<String, Object> m = new HashMap<>();
        m.put("spuId", spu.getId());
        m.put("name", spu.getName());
        m.put("subtitle", spu.getSubtitle());
        m.put("pic", spu.getPics() == null || spu.getPics().isEmpty() ? null : spu.getPics().get(0));
        m.put("soldCount", spu.getSoldCount() == null ? 0 : spu.getSoldCount());
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spu.getId())
                .eq(Sku::getStatus, "ENABLED"));
        BigDecimal min = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = skus.stream().map(Sku::getPrice).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(min);
        m.put("price", min);
        m.put("priceMax", max.compareTo(min) == 0 ? null : max);
        return m;
    }

    private int available(Long skuId) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, skuId).last("LIMIT 1"));
        if (stock == null) {
            return 0;
        }
        return Math.max(0, (stock.getPhysicalStock() == null ? 0 : stock.getPhysicalStock())
                - (stock.getLockedStock() == null ? 0 : stock.getLockedStock()));
    }

    private List<Map<String, Object>> buildTree(Long parentId, Map<Long, List<Category>> byParent) {
        List<Category> children = byParent.getOrDefault(parentId, List.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category c : children) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("children", buildTree(c.getId(), byParent));
            result.add(m);
        }
        return result;
    }
}
