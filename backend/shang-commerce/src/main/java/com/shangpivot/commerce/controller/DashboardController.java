package com.shangpivot.commerce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.AfterSale;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.mapper.AfterSaleMapper;
import com.shangpivot.commerce.mapper.OrderMapper;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.commerce.mapper.StockMapper;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "工作台", description = "管理端工作台概览统计")
public class DashboardController {
    private final OrderMapper orderMapper;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final StockMapper stockMapper;
    private final AfterSaleMapper afterSaleMapper;

    @GetMapping("/stats")
    @Operation(summary = "获取工作台统计", description = "返回在售 SPU、SKU 总数、低库存预警、待发货订单等")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        data.put("pendingShipOrders", orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getStatus, "PENDING_SHIP")));
        data.put("onShelfProducts", spuMapper.selectCount(new LambdaQueryWrapper<Spu>().eq(Spu::getStatus, "ON")));
        data.put("skuTotal", skuMapper.selectCount(null));
        data.put("pendingAfterSales", afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getStatus, "PENDING_AUDIT")));
        List<Stock> stocks = stockMapper.selectList(null);
        long lowStock = stocks.stream().filter(s -> {
            int physical = s.getPhysicalStock() == null ? 0 : s.getPhysicalStock();
            int locked = s.getLockedStock() == null ? 0 : s.getLockedStock();
            int threshold = s.getLowThreshold() == null ? 0 : s.getLowThreshold();
            return physical - locked <= threshold;
        }).count();
        data.put("lowStockCount", lowStock);
        data.put("orderTotal", orderMapper.selectCount(null));
        return Result.ok(data);
    }
}
