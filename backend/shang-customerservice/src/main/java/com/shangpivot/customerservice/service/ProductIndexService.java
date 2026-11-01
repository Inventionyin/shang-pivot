package com.shangpivot.customerservice.service;

import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.commerce.api.ProductSnapshotDTO;
import com.shangpivot.customerservice.llm.EmbeddingClient;
import com.shangpivot.customerservice.mapper.ProductChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品切片索引：数据源 commerce-api 快照（红线：不直读 commerce 表）。
 * 词法模式也维护切片（名称/副标题/类目），向量列在 embedding 可用时回填（ADR-0006）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cs.product-index.enabled", havingValue = "true", matchIfMissing = true)
public class ProductIndexService {

    private static final int BATCH = 100;

    private final ProductQueryApi productQueryApi;
    private final ProductChunkMapper productChunkMapper;
    private final EmbeddingClient embeddingClient;

    /** 增量索引（每 10 分钟）：追平新增在售商品。 */
    @Scheduled(cron = "0 */10 * * * *")
    public void incremental() {
        try {
            long last = productChunkMapper.maxIndexedSpuId();
            int n = syncRange(last);
            if (n > 0) {
                log.info("[cs] 商品索引增量完成：+{}（from spuId>{})", n, last);
            }
        } catch (Exception e) {
            log.warn("[cs] 商品索引增量失败: {}", e.getMessage());
        }
    }

    /** 全量重建（管理端触发）；返回统计。 */
    public Map<String, Object> rebuildFull() {
        long total = productQueryApi.countOnSale();
        int updated = syncRange(0);
        Map<String, Object> r = new HashMap<>();
        r.put("onSale", total);
        r.put("indexed", updated);
        r.put("indexedTotal", productChunkMapper.countIndexed());
        r.put("embeddingReady", embeddingClient.ready());
        return r;
    }

    private int syncRange(long lastSpuId) {
        int count = 0;
        while (true) {
            List<ProductSnapshotDTO> batch = productQueryApi.snapshotPage(lastSpuId, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (ProductSnapshotDTO s : batch) {
                upsert(s);
                lastSpuId = Math.max(lastSpuId, s.spuId());
                count++;
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        return count;
    }

    private void upsert(ProductSnapshotDTO s) {
        boolean embedReady = embeddingClient.ready();
        String embedding = null;
        if (embedReady) {
            try {
                embedding = embeddingClient.embed(indexText(s));
            } catch (Exception e) {
                log.warn("[cs] 商品 {} 向量化失败，落词法切片: {}", s.spuId(), e.getMessage());
            }
        }
        String emb = embedding;
        int updated = emb != null
                ? productChunkMapper.updateWithVector(s.spuId(), s.name(), s.subtitle(), s.categoryName(),
                        s.priceMin(), s.priceMax(), s.soldCount(), emb)
                : productChunkMapper.updateLexical(s.spuId(), s.name(), s.subtitle(), s.categoryName(),
                        s.priceMin(), s.priceMax(), s.soldCount());
        if (updated == 0) {
            long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            if (emb != null) {
                productChunkMapper.upsertWithVector(id, s.spuId(), s.name(), s.subtitle(), s.categoryName(),
                        s.priceMin(), s.priceMax(), s.soldCount(), emb);
            } else {
                productChunkMapper.upsertLexical(id, s.spuId(), s.name(), s.subtitle(), s.categoryName(),
                        s.priceMin(), s.priceMax(), s.soldCount());
            }
        }
    }

    private static String indexText(ProductSnapshotDTO s) {
        StringBuilder sb = new StringBuilder(s.name() == null ? "" : s.name());
        if (s.subtitle() != null) {
            sb.append(' ').append(s.subtitle());
        }
        if (s.categoryName() != null) {
            sb.append(' ').append(s.categoryName());
        }
        return sb.toString();
    }
}
