package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.api.ProductSkuApi;
import com.shangpivot.commerce.api.ProductSkuDTO;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Stock;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductSkuApiImpl implements ProductSkuApi {

    private final SkuMapper skuMapper;
    private final StockMapper stockMapper;

    @Override
    public List<ProductSkuDTO> listEnabledBySpu(Long spuId) {
        if (spuId == null) {
            return Collections.emptyList();
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getStatus, "ENABLED")
                .orderByAsc(Sku::getPrice));
        return skus.stream()
                .map(sku -> new ProductSkuDTO(
                        sku.getId(),
                        sku.getSpuId(),
                        sku.getTitle(),
                        sku.getSpecs(),
                        sku.getPrice(),
                        sku.getPic(),
                        availableStock(sku.getId())
                ))
                .toList();
    }

    private int availableStock(Long skuId) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getSkuId, skuId)
                .last("LIMIT 1"));
        if (stock == null) {
            return 0;
        }
        return Math.max(0, (stock.getPhysicalStock() == null ? 0 : stock.getPhysicalStock())
                - (stock.getLockedStock() == null ? 0 : stock.getLockedStock()));
    }
}
