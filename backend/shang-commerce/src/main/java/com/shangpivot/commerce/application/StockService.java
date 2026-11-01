package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.entity.StockLog;
import com.shangpivot.commerce.entity.Warehouse;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.StockLogMapper;
import com.shangpivot.commerce.mapper.StockMapper;
import com.shangpivot.commerce.mapper.WarehouseMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 库存查询与手工调整。
 * <p>不变式：physical_stock ≥ locked_stock ≥ 0；available = physical − locked。
 * <p>关联表：stock、stock_log、sku。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final SkuMapper skuMapper;
    private final WarehouseMapper warehouseMapper;

    public PageResult<Map<String, Object>> page(int page, int size, Long skuId, Long warehouseId,
                                                Boolean lowOnly, Boolean warnOnly) {
        LambdaQueryWrapper<Stock> q = new LambdaQueryWrapper<>();
        q.eq(skuId != null, Stock::getSkuId, skuId)
                .eq(warehouseId != null, Stock::getWarehouseId, warehouseId);
        if (Boolean.TRUE.equals(lowOnly)) {
            // 低库存档：可售 ≤ 阈值。必须下推到 SQL —— 页内过滤会把结果切碎，
            // 让「低库存 / 关注」Tab 每页只看到零散几行，total 也不对。
            q.apply("physical_stock - locked_stock <= low_threshold");
            // 越缺越靠前（与阈值的差值最小 = 最紧急），便于照单补货。
            // count 查询会由 PaginationInnerInterceptor 剥掉 ORDER BY，见 MybatisPlusConfig。
            q.last("ORDER BY (physical_stock - locked_stock - low_threshold) ASC, id ASC");
        } else if (Boolean.TRUE.equals(warnOnly)) {
            // 预警档（低库存 + 关注）：可售 ≤ 阈值 × 1.5
            q.apply("physical_stock - locked_stock <= low_threshold * 1.5");
            q.last("ORDER BY (physical_stock - locked_stock - low_threshold) ASC, id ASC");
        } else {
            q.orderByDesc(Stock::getId);
        }
        Page<Stock> p = stockMapper.selectPage(new Page<>(page, size), q);
        Map<Long, Sku> skuMap = p.getRecords().isEmpty() ? Map.of() :
                skuMapper.selectBatchIds(p.getRecords().stream().map(Stock::getSkuId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(Sku::getId, s -> s));
        Set<Long> whIds = p.getRecords().stream().map(Stock::getWarehouseId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> whNames = whIds.isEmpty() ? Map.of() :
                warehouseMapper.selectBatchIds(whIds).stream()
                        .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName, (a, b) -> a));
        List<Map<String, Object>> records = p.getRecords().stream().map(s -> {
            Map<String, Object> m = toVo(s);
            Sku sku = skuMap.get(s.getSkuId());
            if (sku != null) {
                m.put("skuTitle", sku.getTitle());
                m.put("barCode", sku.getBarCode());
            }
            Long wid = s.getWarehouseId();
            m.put("warehouseName", whNames.getOrDefault(wid, wid == null ? "—" : "仓库#" + wid));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    /**
     * 手工增减物理库存并写 ADJUST 流水；无记录则先建仓；须保持 physical≥locked≥0。
     */
    @Transactional
    public void adjust(AdjustReq req) {
        if (req.getChangeQty() == null || req.getChangeQty() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "调整数量不能为0");
        }
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getSkuId, req.getSkuId())
                .eq(Stock::getWarehouseId, req.getWarehouseId() == null ? 1L : req.getWarehouseId()));
        if (stock == null) {
            stock = new Stock();
            stock.setSkuId(req.getSkuId());
            stock.setWarehouseId(req.getWarehouseId() == null ? 1L : req.getWarehouseId());
            stock.setPhysicalStock(0);
            stock.setLockedStock(0);
            stock.setLowThreshold(10);
            stockMapper.insert(stock);
        }
        int nextPhysical = stock.getPhysicalStock() + req.getChangeQty();
        int locked = stock.getLockedStock() == null ? 0 : stock.getLockedStock();
        if (nextPhysical < locked || locked < 0) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "调整后违反库存不变式 physical≥locked≥0");
        }
        stock.setPhysicalStock(nextPhysical);
        stockMapper.updateById(stock);

        StockLog stockLog = new StockLog();
        stockLog.setSkuId(stock.getSkuId());
        stockLog.setWarehouseId(stock.getWarehouseId());
        stockLog.setChangeQty(req.getChangeQty());
        stockLog.setType("ADJUST");
        // V1 固定原因文案（PRD §8.12）
        stockLog.setReason("后台手工调整");
        stockLog.setOperatorId(SecurityContexts.requireUserId());
        stockLogMapper.insert(stockLog);
        log.info("Stock adjusted skuId={} warehouseId={} changeQty={} physical={}",
                stock.getSkuId(), stock.getWarehouseId(), req.getChangeQty(), nextPhysical);
    }

    private Map<String, Object> toVo(Stock s) {
        int physical = s.getPhysicalStock() == null ? 0 : s.getPhysicalStock();
        int locked = s.getLockedStock() == null ? 0 : s.getLockedStock();
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("skuId", s.getSkuId());
        m.put("warehouseId", s.getWarehouseId());
        m.put("physicalStock", physical);
        m.put("lockedStock", locked);
        m.put("availableStock", physical - locked);
        m.put("lowThreshold", s.getLowThreshold());
        return m;
    }

    @Data
    @Schema(description = "库存调整请求")
    public static class AdjustReq {
        @Schema(description = "SKU ID", example = "1")
        private Long skuId;
        @Schema(description = "仓库ID，不传默认 1", example = "1")
        private Long warehouseId;
        @Schema(description = "调整数量，正数入库、负数出库", example = "10")
        private Integer changeQty;
        @Schema(description = "调整原因", example = "盘点盈余")
        private String reason;
    }
}
