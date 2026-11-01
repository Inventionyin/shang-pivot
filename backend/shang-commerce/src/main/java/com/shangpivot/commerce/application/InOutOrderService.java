package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.InOutItem;
import com.shangpivot.commerce.entity.InOutOrder;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.entity.StockLog;
import com.shangpivot.commerce.mapper.InOutItemMapper;
import com.shangpivot.commerce.mapper.InOutOrderMapper;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 出入库单据：采购入 / 盘点 / 销售出（发货自动）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InOutOrderService {

    private static final Set<String> MANUAL_TYPES = Set.of("PURCHASE_IN", "STOCKTAKE");
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private final InOutOrderMapper inOutOrderMapper;
    private final InOutItemMapper inOutItemMapper;
    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;

    public PageResult<Map<String, Object>> page(int page, int size, String type, String billNo, Long skuId,
                                                OffsetDateTime from, OffsetDateTime to) {
        LambdaQueryWrapper<InOutOrder> q = new LambdaQueryWrapper<>();
        q.eq(StringUtils.hasText(type), InOutOrder::getType, type)
                .like(StringUtils.hasText(billNo), InOutOrder::getBillNo, billNo)
                .ge(from != null, InOutOrder::getCreatedAt, from)
                .le(to != null, InOutOrder::getCreatedAt, to)
                .orderByDesc(InOutOrder::getCreatedAt);
        Page<InOutOrder> p = inOutOrderMapper.selectPage(new Page<>(page, size), q);
        List<Map<String, Object>> records = new ArrayList<>();
        for (InOutOrder bill : p.getRecords()) {
            List<InOutItem> items = inOutItemMapper.selectList(new LambdaQueryWrapper<InOutItem>()
                    .eq(InOutItem::getBillId, bill.getId()));
            if (skuId != null && items.stream().noneMatch(i -> skuId.equals(i.getSkuId()))) {
                continue;
            }
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (InOutItem it : items) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", it.getId());
                row.put("skuId", it.getSkuId());
                row.put("qty", it.getQty());
                row.put("remark", it.getRemark());
                row.put("skuTitle", resolveSkuTitle(it.getSkuId()));
                enriched.add(row);
            }
            Map<String, Object> m = new HashMap<>();
            m.put("bill", bill);
            m.put("items", enriched);
            records.add(m);
        }
        return PageResult.of(p.getTotal(), records);
    }

    private String resolveSkuTitle(Long skuId) {
        if (skuId == null) {
            return "—";
        }
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            return String.valueOf(skuId);
        }
        if (StringUtils.hasText(sku.getTitle())) {
            return sku.getTitle();
        }
        if (sku.getSpuId() != null) {
            Spu spu = spuMapper.selectById(sku.getSpuId());
            if (spu != null && StringUtils.hasText(spu.getName())) {
                return spu.getName();
            }
        }
        return String.valueOf(skuId);
    }

    @Transactional
    public Long createManual(CreateReq req) {
        if (!MANUAL_TYPES.contains(req.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手工单据仅支持 PURCHASE_IN / STOCKTAKE");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "明细不能为空");
        }
        Long warehouseId = req.getWarehouseId() == null ? 1L : req.getWarehouseId();
        InOutOrder bill = newInOut(req.getType(), warehouseId, req.getRemark(), null);
        for (ItemReq itemReq : req.getItems()) {
            if (itemReq.getSkuId() == null || itemReq.getQty() == null || itemReq.getQty() == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "SKU 与数量必填且数量非 0");
            }
            int signedQty = "PURCHASE_IN".equals(req.getType())
                    ? Math.abs(itemReq.getQty())
                    : itemReq.getQty(); // STOCKTAKE 可为正负
            applyStockChange(itemReq.getSkuId(), warehouseId, signedQty, req.getType(), bill.getBillNo());
            InOutItem item = new InOutItem();
            item.setBillId(bill.getId());
            item.setSkuId(itemReq.getSkuId());
            item.setQty(signedQty);
            item.setRemark(itemReq.getRemark());
            inOutItemMapper.insert(item);
        }
        return bill.getId();
    }

    /** 发货同事务调用：生成 SALE_OUT 并扣减 physical/locked。 */
    @Transactional
    public void createSaleOut(String orderNo, Long warehouseId, List<ItemReq> items, Long operatorId) {
        InOutOrder bill = newInOut("SALE_OUT", warehouseId == null ? 1L : warehouseId, "订单发货出库", orderNo);
        bill.setOperatorId(operatorId);
        inOutOrderMapper.updateById(bill);
        for (ItemReq itemReq : items) {
            int qty = -Math.abs(itemReq.getQty());
            InOutItem item = new InOutItem();
            item.setBillId(bill.getId());
            item.setSkuId(itemReq.getSkuId());
            item.setQty(qty);
            item.setRemark(itemReq.getRemark());
            inOutItemMapper.insert(item);
        }
    }

    private InOutOrder newInOut(String type, Long warehouseId, String remark, String relatedOrderNo) {
        InOutOrder bill = new InOutOrder();
        bill.setBillNo(nextBillNo(type));
        bill.setType(type);
        bill.setWarehouseId(warehouseId);
        bill.setRemark(remark);
        bill.setRelatedOrderNo(relatedOrderNo);
        try {
            bill.setOperatorId(SecurityContexts.requireUserId());
        } catch (BizException ignored) {
            // job / system
        }
        inOutOrderMapper.insert(bill);
        return bill;
    }

    private void applyStockChange(Long skuId, Long warehouseId, int changeQty, String type, String billNo) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getSkuId, skuId)
                .eq(Stock::getWarehouseId, warehouseId));
        if (stock == null) {
            stock = new Stock();
            stock.setSkuId(skuId);
            stock.setWarehouseId(warehouseId);
            stock.setPhysicalStock(0);
            stock.setLockedStock(0);
            stock.setLowThreshold(10);
            stockMapper.insert(stock);
        }
        int nextPhysical = stock.getPhysicalStock() + changeQty;
        int locked = stock.getLockedStock() == null ? 0 : stock.getLockedStock();
        if (nextPhysical < locked || locked < 0) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "出入库后违反库存不变式");
        }
        stock.setPhysicalStock(nextPhysical);
        stockMapper.updateById(stock);

        StockLog stockLog = new StockLog();
        stockLog.setSkuId(skuId);
        stockLog.setWarehouseId(warehouseId);
        stockLog.setChangeQty(changeQty);
        stockLog.setType(type);
        stockLog.setReason("出入库单 " + billNo);
        try {
            stockLog.setOperatorId(SecurityContexts.requireUserId());
        } catch (BizException ignored) {
        }
        stockLogMapper.insert(stockLog);
    }

    private String nextBillNo(String type) {
        String prefix = switch (type) {
            case "PURCHASE_IN" -> "PI";
            case "STOCKTAKE" -> "ST";
            case "SALE_OUT" -> "SO";
            default -> "IO";
        };
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%04d", SEQ.getAndIncrement() % 10000);
    }

    @Data
    public static class CreateReq {
        @Schema(allowableValues = {"PURCHASE_IN", "STOCKTAKE"})
        private String type;
        private Long warehouseId;
        private String remark;
        private List<ItemReq> items;
    }

    @Data
    public static class ItemReq {
        private Long skuId;
        private Integer qty;
        private String remark;
    }
}
