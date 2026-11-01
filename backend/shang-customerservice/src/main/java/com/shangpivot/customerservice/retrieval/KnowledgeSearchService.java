package com.shangpivot.customerservice.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.customerservice.entity.KnowledgeDoc;
import com.shangpivot.customerservice.mapper.KnowledgeChunkMapper;
import com.shangpivot.customerservice.mapper.KnowledgeDocMapper;
import com.shangpivot.customerservice.llm.EmbeddingClient;
import com.shangpivot.customerservice.llm.LlmSettingsResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识检索：向量（chunk.embedding）/ 词法（chunk bigram → 空则直接 LIKE knowledge_doc.content）。
 * 仅发布态文档参与。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {

    private final LlmSettingsResolver settingsResolver;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;

    public record KnowledgeHit(Long docId, String title, String snippet, double score, String source) {
    }

    public List<KnowledgeHit> search(String query, int topK) {
        List<KnowledgeDoc> published = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_PUBLISHED)
                .orderByDesc(KnowledgeDoc::getUpdatedAt));
        if (published.isEmpty()) {
            return List.of();
        }
        String mode = settingsResolver.resolve().getRetrievalMode();
        if (!"lexical".equals(mode) && settingsResolver.resolve().embeddingReady()) {
            try {
                String vec = embeddingClient.embed(query);
                List<Map<String, Object>> rows = knowledgeChunkMapper.searchVector(vec, topK);
                if (!rows.isEmpty()) {
                    return rows.stream()
                            .map(r -> new KnowledgeHit(
                                    ((Number) r.get("doc_id")).longValue(),
                                    titleOf(published, ((Number) r.get("doc_id")).longValue()),
                                    String.valueOf(r.get("chunk_text")),
                                    ((Number) r.get("score")).doubleValue(),
                                    "vector"))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("[cs] 向量知识检索失败，回落词法: {}", e.getMessage());
            }
        }
        List<String> grams = TextGrams.of(query, 8);
        if (!grams.isEmpty()) {
            try {
                List<Map<String, Object>> rows = knowledgeChunkMapper.searchLexical(grams, topK);
                if (!rows.isEmpty()) {
                    return rows.stream()
                            .map(r -> new KnowledgeHit(
                                    ((Number) r.get("doc_id")).longValue(),
                                    titleOf(published, ((Number) r.get("doc_id")).longValue()),
                                    String.valueOf(r.get("chunk_text")),
                                    ((Number) r.get("score")).doubleValue(),
                                    "lexical"))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("[cs] 知识切片词法检索失败，回落全文: {}", e.getMessage());
            }
        }
        // 兜底：直接对发布文档全文打分（开箱可用，切片未建也有效）
        return published.stream()
                .map(d -> {
                    long score = TextGrams.of(query, 8).stream()
                            .filter(g -> d.getContent() != null && d.getContent().contains(g))
                            .count();
                    return new KnowledgeHit(d.getId(), d.getTitle(),
                            snippet(d.getContent()), score, "content");
                })
                .filter(h -> h.score() > 0)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .toList();
    }

    private String titleOf(List<KnowledgeDoc> docs, Long docId) {
        return docs.stream().filter(d -> d.getId().equals(docId)).findFirst()
                .map(KnowledgeDoc::getTitle).orElse("知识文档#" + docId);
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 400 ? content.substring(0, 400) : content;
    }
}
