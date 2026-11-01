package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.Category;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.entity.StockLog;
import com.shangpivot.commerce.mapper.CategoryMapper;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.commerce.mapper.StockLogMapper;
import com.shangpivot.commerce.mapper.StockMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品（SPU/SKU）用例：分页、详情、创建更新、上下架与删除。
 * <p>上架需主图、可售 SKU 与合法价格；新建 SKU 可初始化 stock / stock_log。
 * <p>关联表：spu、sku、stock、stock_log。
 */
@Service
@RequiredArgsConstructor
public class ProductService {
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final CategoryMapper categoryMapper;

    /** 分页查询；附带类目名、SKU 数、可售库存与价格区间。 */
    public PageResult<SpuListItem> page(int page, int size, String name, String status, Long categoryId) {
        LambdaQueryWrapper<Spu> q = new LambdaQueryWrapper<>();
        q.like(StringUtils.hasText(name), Spu::getName, name)
                .eq(StringUtils.hasText(status), Spu::getStatus, status)
                .eq(categoryId != null, Spu::getCategoryId, categoryId)
                .orderByDesc(Spu::getId);
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size), q);
        return PageResult.of(p.getTotal(), enrichList(p.getRecords()));
    }

    private List<SpuListItem> enrichList(List<Spu> spus) {
        if (CollectionUtils.isEmpty(spus)) {
            return List.of();
        }
        Set<Long> categoryIds = spus.stream()
                .map(Spu::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category c : categoryMapper.selectBatchIds(categoryIds)) {
                categoryNames.put(c.getId(), c.getName());
            }
        }

        List<Long> spuIds = spus.stream().map(Spu::getId).toList();
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>().in(Sku::getSpuId, spuIds));
        Map<Long, List<Sku>> skusBySpu = skus.stream().collect(Collectors.groupingBy(Sku::getSpuId));

        Set<Long> skuIds = skus.stream().map(Sku::getId).collect(Collectors.toCollection(HashSet::new));
        Map<Long, Integer> availableBySku = new HashMap<>();
        if (!skuIds.isEmpty()) {
            for (Stock st : stockMapper.selectList(new LambdaQueryWrapper<Stock>().in(Stock::getSkuId, skuIds))) {
                int physical = st.getPhysicalStock() == null ? 0 : st.getPhysicalStock();
                int locked = st.getLockedStock() == null ? 0 : st.getLockedStock();
                availableBySku.merge(st.getSkuId(), Math.max(0, physical - locked), Integer::sum);
            }
        }

        List<SpuListItem> rows = new ArrayList<>(spus.size());
        for (Spu spu : spus) {
            List<Sku> group = skusBySpu.getOrDefault(spu.getId(), List.of());
            BigDecimal min = null;
            BigDecimal max = null;
            int stockSum = 0;
            for (Sku sku : group) {
                if (sku.getPrice() != null) {
                    min = min == null ? sku.getPrice() : min.min(sku.getPrice());
                    max = max == null ? sku.getPrice() : max.max(sku.getPrice());
                }
                stockSum += availableBySku.getOrDefault(sku.getId(), 0);
            }
            SpuListItem item = new SpuListItem();
            item.setId(spu.getId());
            item.setName(spu.getName());
            item.setCategoryId(spu.getCategoryId());
            item.setCategoryName(categoryNames.get(spu.getCategoryId()));
            item.setBrandId(spu.getBrandId());
            item.setStatus(spu.getStatus());
            item.setPics(spu.getPics());
            item.setSkuCount(group.size());
            item.setStock(stockSum);
            item.setMinPrice(min);
            item.setMaxPrice(max);
            item.setPriceRange(formatPriceRange(min, max));
            rows.add(item);
        }
        return rows;
    }

    private static String formatPriceRange(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return "—";
        }
        if (min == null) {
            return "¥" + money(max);
        }
        if (max == null || min.compareTo(max) == 0) {
            return "¥" + money(min);
        }
        return "¥" + money(min) + " ~ ¥" + money(max);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public Map<String, Object> detail(Long id) {
        Spu spu = requireSpu(id);
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>().eq(Sku::getSpuId, id));
        Map<Long, Integer> stockBySku = new HashMap<>();
        if (!skus.isEmpty()) {
            List<Long> skuIds = skus.stream().map(Sku::getId).toList();
            for (Stock st : stockMapper.selectList(new LambdaQueryWrapper<Stock>().in(Stock::getSkuId, skuIds))) {
                int physical = st.getPhysicalStock() == null ? 0 : st.getPhysicalStock();
                int locked = st.getLockedStock() == null ? 0 : st.getLockedStock();
                stockBySku.merge(st.getSkuId(), Math.max(0, physical - locked), Integer::sum);
            }
        }
        List<Map<String, Object>> skuRows = new ArrayList<>(skus.size());
        for (Sku sku : skus) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", sku.getId());
            row.put("spuId", sku.getSpuId());
            row.put("title", sku.getTitle());
            row.put("price", sku.getPrice());
            row.put("costPrice", sku.getCostPrice());
            row.put("barCode", sku.getBarCode());
            row.put("specs", sku.getSpecs());
            row.put("pic", sku.getPic());
            row.put("status", sku.getStatus());
            row.put("stock", stockBySku.getOrDefault(sku.getId(), 0));
            skuRows.add(row);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("spu", spu);
        data.put("skus", skuRows);
        return data;
    }

    /** 新建 SPU+SKU；默认 DRAFT，可按 initStock 初始化库存与 INIT 流水。 */
    @Transactional
    public Long create(ProductSaveReq req) {
        Spu spu = req.toSpu();
        if (!StringUtils.hasText(spu.getStatus())) {
            spu.setStatus("DRAFT");
        }
        if (req.getScheduleOnAt() != null) {
            if (!req.getScheduleOnAt().isAfter(java.time.OffsetDateTime.now())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "定时上架时间须晚于当前");
            }
            spu.setScheduleOnAt(req.getScheduleOnAt());
        }
        spuMapper.insert(spu);
        saveSkus(spu.getId(), req.getSkus(), true);
        return spu.getId();
    }

    /** 更新 SPU；若带 skus 则删旧建新（不强制重建库存）。 */
    @Transactional
    public void update(Long id, ProductSaveReq req) {
        Spu spu = requireSpu(id);
        spu.setCategoryId(req.getCategoryId());
        spu.setBrandId(req.getBrandId());
        spu.setName(req.getName());
        spu.setSubtitle(req.getSubtitle());
        spu.setPics(req.getPics());
        spu.setVideos(req.getVideos());
        spu.setDetailHtml(req.getDetailHtml());
        if (StringUtils.hasText(req.getStatus())) {
            spu.setStatus(req.getStatus());
        }
        // 定时上架：有 scheduleOnAt 则写入并保持非 ON（除非显式立即上架）
        if (req.getScheduleOnAt() != null) {
            if (!req.getScheduleOnAt().isAfter(java.time.OffsetDateTime.now())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "定时上架时间须晚于当前");
            }
            spu.setScheduleOnAt(req.getScheduleOnAt());
            if (!"ON".equals(spu.getStatus())) {
                // 保持草稿/下架，等 Job
            }
        } else if (Boolean.TRUE.equals(req.getClearScheduleOnAt())) {
            spu.setScheduleOnAt(null);
        }
        spuMapper.updateById(spu);
        if (req.getSkus() != null) {
            List<Sku> old = skuMapper.selectList(new LambdaQueryWrapper<Sku>().eq(Sku::getSpuId, id));
            for (Sku s : old) {
                skuMapper.deleteById(s.getId());
            }
            saveSkus(id, req.getSkus(), false);
        }
    }

    /** 变更上下架状态；ON 时校验主图 / 可售 SKU / 价格。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        Spu spu = requireSpu(id);
        if ("ON".equals(status)) {
            validateOnShelf(spu);
            spu.setScheduleOnAt(null);
        }
        spu.setStatus(status);
        spuMapper.updateById(spu);
    }

    /** 删除商品；仅草稿（DRAFT）可删。 */
    public void delete(Long id) {
        Spu spu = requireSpu(id);
        if (!"DRAFT".equals(spu.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "仅草稿商品可删除，上架/下架请先处理状态");
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>().eq(Sku::getSpuId, id));
        for (Sku sku : skus) {
            stockMapper.delete(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, sku.getId()));
            skuMapper.deleteById(sku.getId());
        }
        spuMapper.deleteById(id);
    }

    /** 批量下架：仅处理当前为 ON 的商品，其余跳过。 */
    @Transactional
    public int batchOff(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要下架的商品");
        }
        int n = 0;
        for (Long id : ids) {
            Spu spu = spuMapper.selectById(id);
            if (spu == null || !"ON".equals(spu.getStatus())) {
                continue;
            }
            spu.setStatus("OFF");
            spuMapper.updateById(spu);
            n++;
        }
        return n;
    }

    private void validateOnShelf(Spu spu) {
        if (CollectionUtils.isEmpty(spu.getPics())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上架需至少一张主图");
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spu.getId())
                .eq(Sku::getStatus, "ENABLED"));
        if (skus.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上架需至少一个可售SKU");
        }
        boolean hasPrice = skus.stream().anyMatch(s -> s.getPrice() != null && s.getPrice().compareTo(BigDecimal.ZERO) > 0);
        if (!hasPrice) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上架需合法价格");
        }
    }

    private void saveSkus(Long spuId, List<SkuSave> skus, boolean initStock) {
        if (skus == null) {
            return;
        }
        for (SkuSave item : skus) {
            Sku sku = new Sku();
            sku.setSpuId(spuId);
            sku.setTitle(item.getTitle());
            sku.setPrice(item.getPrice());
            sku.setCostPrice(item.getCostPrice());
            sku.setBarCode(item.getBarCode());
            sku.setSpecs(item.getSpecs());
            sku.setPic(item.getPic());
            sku.setStatus(StringUtils.hasText(item.getStatus()) ? item.getStatus() : "ENABLED");
            skuMapper.insert(sku);
            if (initStock || stockMapper.selectCount(new LambdaQueryWrapper<Stock>().eq(Stock::getSkuId, sku.getId())) == 0) {
                Stock stock = new Stock();
                stock.setSkuId(sku.getId());
                stock.setWarehouseId(item.getWarehouseId() == null ? 1L : item.getWarehouseId());
                int qty = item.getInitStock() == null ? 0 : item.getInitStock();
                stock.setPhysicalStock(qty);
                stock.setLockedStock(0);
                stock.setLowThreshold(item.getLowThreshold() == null ? 10 : item.getLowThreshold());
                stockMapper.insert(stock);
                if (qty > 0) {
                    StockLog log = new StockLog();
                    log.setSkuId(sku.getId());
                    log.setWarehouseId(stock.getWarehouseId());
                    log.setChangeQty(qty);
                    log.setType("INIT");
                    log.setReason("SKU初始化");
                    log.setOperatorId(SecurityContexts.get() == null ? null : SecurityContexts.get().getUserId());
                    stockLogMapper.insert(log);
                }
            }
        }
    }

    private Spu requireSpu(Long id) {
        Spu spu = spuMapper.selectById(id);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return spu;
    }

    @Data
    @Schema(description = "商品列表项（含聚合字段）")
    public static class SpuListItem {
        @Schema(description = "SPU ID")
        private Long id;
        @Schema(description = "商品名称")
        private String name;
        @Schema(description = "类目 ID")
        private Long categoryId;
        @Schema(description = "类目名称")
        private String categoryName;
        @Schema(description = "品牌 ID")
        private Long brandId;
        @Schema(description = "状态：ON / OFF / DRAFT")
        private String status;
        @Schema(description = "主图列表")
        private List<String> pics;
        @Schema(description = "SKU 数量")
        private Integer skuCount;
        @Schema(description = "可售库存合计（物理库存 - 锁定）")
        private Integer stock;
        @Schema(description = "最低价")
        private BigDecimal minPrice;
        @Schema(description = "最高价")
        private BigDecimal maxPrice;
        @Schema(description = "价格区间展示文案", example = "¥7999 ~ ¥10999")
        private String priceRange;
    }

    @Data
    @Schema(description = "商品保存请求")
    public static class ProductSaveReq {
        @Schema(description = "类目ID", example = "1")
        private Long categoryId;
        @Schema(description = "品牌ID", example = "1")
        private Long brandId;
        @Schema(description = "商品名称", example = "iPhone 16 Pro")
        private String name;
        @Schema(description = "副标题", example = "A18 Pro 芯片，钛金属机身")
        private String subtitle;
        @Schema(description = "主图 URL 列表", example = "[\"https://cdn.example.com/spu/1.jpg\"]")
        private List<String> pics;
        @Schema(description = "视频 URL 列表", example = "[\"https://cdn.example.com/spu/1.mp4\"]")
        private List<String> videos;
        @Schema(description = "详情 HTML", example = "<p>商品详情</p>")
        private String detailHtml;
        @Schema(description = "商品状态：ON / OFF / DRAFT", example = "DRAFT")
        private String status;
        @Schema(description = "定时上架时间（须晚于当前）；与立即上架互斥")
        private java.time.OffsetDateTime scheduleOnAt;
        @Schema(description = "是否清空定时上架时间")
        private Boolean clearScheduleOnAt;
        @Schema(description = "SKU 列表")
        private List<SkuSave> skus;

        Spu toSpu() {
            Spu spu = new Spu();
            spu.setCategoryId(categoryId);
            spu.setBrandId(brandId);
            spu.setName(name);
            spu.setSubtitle(subtitle);
            spu.setPics(pics);
            spu.setVideos(videos);
            spu.setDetailHtml(detailHtml);
            spu.setStatus(status);
            spu.setScheduleOnAt(scheduleOnAt);
            return spu;
        }
    }

    @Data
    @Schema(description = "SKU 保存项")
    public static class SkuSave {
        @Schema(description = "SKU 标题", example = "256GB 沙漠钛")
        private String title;
        @Schema(description = "销售价", example = "8999.00")
        private BigDecimal price;
        @Schema(description = "成本价", example = "7500.00")
        private BigDecimal costPrice;
        @Schema(description = "条形码", example = "6901234567890")
        private String barCode;
        @Schema(description = "规格键值", example = "{\"颜色\":\"沙漠钛\",\"容量\":\"256GB\"}")
        private Map<String, Object> specs;
        @Schema(description = "SKU 图片 URL", example = "https://cdn.example.com/sku/1.jpg")
        private String pic;
        @Schema(description = "SKU 状态：ENABLED / DISABLED", example = "ENABLED")
        private String status;
        @Schema(description = "初始库存数量", example = "100")
        private Integer initStock;
        @Schema(description = "仓库ID", example = "1")
        private Long warehouseId;
        @Schema(description = "低库存预警阈值", example = "10")
        private Integer lowThreshold;
    }
}
