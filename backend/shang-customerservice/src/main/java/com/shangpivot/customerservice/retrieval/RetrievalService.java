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

import java.util.List;
import java.util.Map;

/**
 * 商品检索：vector（pgvector）/ lexical（bigram，经 commerce-api 兜底直查）双路径，模式见 ADR-0006。
 * 返回的卡片一律来自真实在售数据（防幻觉红线）。
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

    public record ProductHit(ProductCardDTO card, double score, String source) {
    }

    /**
     * 商品检索：向量优先（配置且可用），异常/无结果自动回落词法。
     */
    public List<ProductHit> searchProducts(String query, int topK) {
        String mode = settingsResolver.resolve().getRetrievalMode();
        if ("lexical".equals(mode)) {
            return lexical(query, topK);
        }
        // auto / vector：先走向量
        try {
            if (settingsResolver.resolve().embeddingReady()) {
                String vec = embeddingClient.embed(query);
                List<Map<String, Object>> rows = productChunkMapper.searchVector(vec, topK);
                if (!rows.isEmpty()) {
                    List<Long> ids = rows.stream().map(r -> ((Number) r.get("spu_id")).longValue()).toList();
                    Map<Long, ProductCardDTO> cards = cardMap(productQueryApi.getBySpuIds(ids));
                    // 相似度门槛：低于阈值的命中视为噪声（否则任何查询都会硬凑出 topK）
                    double minScore = minScore();
                    List<ProductHit> hits = rows.stream()
                            .filter(r -> cards.containsKey(((Number) r.get("spu_id")).longValue()))
                            .map(r -> new ProductHit(
                                    cards.get(((Number) r.get("spu_id")).longValue()),
                                    ((Number) r.get("score")).doubleValue(),
                                    "vector"))
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
        return lexical(query, topK);
    }

    private List<ProductHit> lexical(String query, int topK) {
        List<String> grams = TextGrams.of(query, 8);
        if (grams.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = productChunkMapper.searchLexical(grams, topK);
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(r -> ((Number) r.get("spu_id")).longValue()).toList();
            Map<Long, ProductCardDTO> cards = cardMap(productQueryApi.getBySpuIds(ids));
            return rows.stream()
                    .filter(r -> cards.containsKey(((Number) r.get("spu_id")).longValue()))
                    .map(r -> new ProductHit(
                            cards.get(((Number) r.get("spu_id")).longValue()),
                            ((Number) r.get("score")).doubleValue(),
                            "lexical"))
                    .toList();
        }
        // 切片表为空（索引未建）→ 直接查 commerce（保证开箱可用）
        return productQueryApi.search(query, topK).stream()
                .map(c -> new ProductHit(c, 0, "commerce"))
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
