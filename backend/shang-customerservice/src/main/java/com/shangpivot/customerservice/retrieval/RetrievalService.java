package com.shangpivot.customerservice.retrieval;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.customerservice.llm.EmbeddingClient;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import com.shangpivot.customerservice.mapper.ProductChunkMapper;
import com.shangpivot.identity.api.SystemConfigApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品检索：vector（pgvector）/ lexical（bigram，经 commerce-api 兜底直查）双路径，模式见 ADR-0006。
 * 返回的卡片一律来自真实在售数据（防幻觉红线）；支持品类/预算硬约束与“超预算候选”标注。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final LlmSettingsResolver settingsResolver;
    private final EmbeddingClient embeddingClient;
    private final ProductChunkMapper productChunkMapper;
    private final ProductQueryApi productQueryApi;
    private final SystemConfigApi systemConfigApi;

    public record ProductHit(ProductCardDTO card, double score, String source, boolean withinBudget) {
        public ProductHit(ProductCardDTO card, double score, String source) {
            this(card, score, source, true);
        }
    }

    /** 无约束检索（兼容旧调用点）。 */
    public List<ProductHit> searchProducts(String query, int topK) {
        return searchProducts(query, topK, ProductConstraints.none());
    }

    /**
     * 带品类/预算约束检索：先查预算内，数量不足时补同品类超预算候选（标记 withinBudget=false）。
     * 品类过滤是硬约束，绝不因为无命中而放宽到其它品类。
     */
    public List<ProductHit> searchProducts(String query, int topK, ProductConstraints constraints) {
        ProductConstraints c = constraints == null ? ProductConstraints.none() : constraints;
        List<ProductHit> hits = new ArrayList<>(searchRaw(query, topK, c));
        if (c.budgetMax() != null && hits.size() < topK) {
            ProductConstraints overBudget = new ProductConstraints(c.categories(), c.budgetMin(), null,
                    c.budgetLabel(), c.explicitNewQuery());
            Map<Long, ProductHit> merged = new LinkedHashMap<>();
            hits.forEach(h -> merged.put(h.card().spuId(), h));
            // 超预算候选保持检索相关性顺序（不按价格重排），
            // 保证“预算两千左右”仍能看到目录里用户最常问的 iPhone 15 等对比项。
            List<ProductHit> candidates = searchRaw(query, Math.max(topK + 10, 20), overBudget).stream()
                    .filter(hit -> hit.card().price() != null
                            && hit.card().price().compareTo(c.budgetMax()) > 0)
                    .toList();
            for (ProductHit hit : candidates) {
                if (merged.size() >= topK) {
                    break;
                }
                merged.putIfAbsent(hit.card().spuId(),
                        new ProductHit(hit.card(), hit.score(), hit.source(), false));
            }
            hits = new ArrayList<>(merged.values());
        }
        if (hits.size() > topK) {
            hits = new ArrayList<>(hits.subList(0, topK));
        }
        // 预算内优先；同组按价格升序，便于用户先看到最贴近预算的候选
        hits.sort(java.util.Comparator
                .comparing(ProductHit::withinBudget).reversed()
                .thenComparing(hit -> hit.card().price() == null ? BigDecimal.ZERO : hit.card().price()));
        return hits;
    }

    private List<ProductHit> searchRaw(String query, int topK, ProductConstraints c) {
        String mode = settingsResolver.resolve().getRetrievalMode();
        if ("lexical".equals(mode)) {
            return lexical(query, topK, c);
        }
        try {
            if (settingsResolver.resolve().embeddingReady()) {
                String vec = embeddingClient.embed(query);
                List<Map<String, Object>> rows = productChunkMapper.searchVector(
                        vec, topK, c.categories(), c.budgetMin(), c.budgetMax());
                if (!rows.isEmpty()) {
                    List<Long> ids = rows.stream().map(r -> ((Number) r.get("spu_id")).longValue()).toList();
                    Map<Long, ProductCardDTO> cards = cardMap(productQueryApi.getBySpuIds(ids));
                    double minScore = minScore();
                    List<ProductHit> hits = rows.stream()
                            .filter(r -> cards.containsKey(((Number) r.get("spu_id")).longValue()))
                            .map(r -> {
                                ProductCardDTO card = cards.get(((Number) r.get("spu_id")).longValue());
                                return new ProductHit(card, ((Number) r.get("score")).doubleValue(), "vector",
                                        c.within(card.price()));
                            })
                            .filter(h -> h.score() >= minScore)
                            .toList();
                    if (!hits.isEmpty()) {
                        return hits;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[cs] 向量商品检索失败，回落词法: {}", e.getMessage());
        }
        return lexical(query, topK, c);
    }

    private List<ProductHit> lexical(String query, int topK, ProductConstraints c) {
        List<String> grams = TextGrams.of(query, 24);
        if (grams.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = productChunkMapper.searchLexical(
                grams, topK, c.categories(), c.budgetMin(), c.budgetMax());
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(r -> ((Number) r.get("spu_id")).longValue()).toList();
            Map<Long, ProductCardDTO> cards = cardMap(productQueryApi.getBySpuIds(ids));
            return rows.stream()
                    .filter(r -> cards.containsKey(((Number) r.get("spu_id")).longValue()))
                    .map(r -> {
                        ProductCardDTO card = cards.get(((Number) r.get("spu_id")).longValue());
                        return new ProductHit(card, ((Number) r.get("score")).doubleValue(), "lexical",
                                c.within(card.price()));
                    })
                    .toList();
        }
        // 切片表为空（索引未建）→ 直接查 commerce；有品类约束时不走该兜底，避免跨品类泄漏
        if (c.hasCategories()) {
            return List.of();
        }
        return productQueryApi.search(query, topK).stream()
                .filter(card -> c.within(card.price()))
                .map(card -> new ProductHit(card, 0, "commerce", c.within(card.price())))
                .toList();
    }

    private double minScore() {
        try {
            return Double.parseDouble(systemConfigApi.getValue("retrieval.min-score", "0.5"));
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    private Map<Long, ProductCardDTO> cardMap(List<ProductCardDTO> cards) {
        return cards.stream().collect(java.util.stream.Collectors.toMap(ProductCardDTO::spuId, c -> c, (a, b) -> a));
    }
}
