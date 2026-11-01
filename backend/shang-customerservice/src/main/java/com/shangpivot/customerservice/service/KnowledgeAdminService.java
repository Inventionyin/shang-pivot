package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.entity.KnowledgeDoc;
import com.shangpivot.customerservice.llm.EmbeddingClient;
import com.shangpivot.customerservice.mapper.KnowledgeChunkMapper;
import com.shangpivot.customerservice.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理（PRD §8.7）：CRUD、发布、切片与向量化重建（EmbeddingClient 可用才向量，否则仅词法切片）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAdminService {

    private static final int CHUNK_SIZE = 500;

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;

    public PageResult<Map<String, Object>> page(String keyword, String status, int page, int size) {
        Page<KnowledgeDoc> p = docMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<KnowledgeDoc>()
                .like(StringUtils.hasText(keyword), KnowledgeDoc::getTitle, keyword)
                .eq(StringUtils.hasText(status), KnowledgeDoc::getStatus, status)
                .orderByDesc(KnowledgeDoc::getId));
        return PageResult.of(p.getTotal(), p.getRecords().stream().map(this::toVo).toList());
    }

    public KnowledgeDoc require(Long id) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null || Boolean.TRUE.equals(doc.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return doc;
    }

    public KnowledgeDoc create(String title, String category, String content, boolean publish) {
        validate(title, content);
        Long dup = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>().eq(KnowledgeDoc::getTitle, title));
        if (dup > 0) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "标题已存在");
        }
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title);
        doc.setCategory(category == null || category.isBlank() ? "FAQ" : category);
        doc.setContent(content);
        doc.setStatus(publish ? KnowledgeDoc.STATUS_PUBLISHED : KnowledgeDoc.STATUS_DRAFT);
        doc.setVersion(1);
        doc.setVectorStatus(KnowledgeDoc.VECTOR_PENDING);
        doc.setChunkCount(0);
        docMapper.insert(doc);
        if (publish) {
            rebuildOne(doc.getId());
        }
        return doc;
    }

    public KnowledgeDoc update(Long id, String title, String category, String content, String status) {
        KnowledgeDoc doc = require(id);
        validate(title, content);
        Long dup = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getTitle, title).ne(KnowledgeDoc::getId, id));
        if (dup > 0) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "标题已存在");
        }
        doc.setTitle(title);
        doc.setCategory(category == null || category.isBlank() ? doc.getCategory() : category);
        doc.setContent(content);
        if (status != null) {
            doc.setStatus(status);
        }
        doc.setVersion(doc.getVersion() == null ? 2 : doc.getVersion() + 1);
        doc.setVectorStatus(KnowledgeDoc.VECTOR_PENDING);
        docMapper.updateById(doc);
        rebuildOne(id);
        return doc;
    }

    public void delete(Long id) {
        require(id);
        docMapper.deleteById(id);
        chunkMapper.softDeleteByDocId(id);
    }

    /** 重建索引：docId 为空 = 全量发布文档；返回统计。返回 Map：processed/failed/vectorized。 */
    public Map<String, Object> rebuild(Long docId, boolean full) {
        List<KnowledgeDoc> docs;
        if (docId != null) {
            docs = List.of(require(docId));
        } else if (full) {
            docs = docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                    .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_PUBLISHED));
        } else {
            docs = docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                    .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_PUBLISHED)
                    .eq(KnowledgeDoc::getVectorStatus, KnowledgeDoc.VECTOR_PENDING));
        }
        int processed = 0;
        int failed = 0;
        int vectorized = 0;
        boolean embedReady = embeddingClient.ready();
        for (KnowledgeDoc doc : docs) {
            try {
                int chunks = rebuildOne(doc.getId());
                processed++;
                if (embedReady && chunks > 0) {
                    vectorized++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("[cs] 知识文档重建失败 id={}: {}", doc.getId(), e.getMessage());
                doc.setVectorStatus(KnowledgeDoc.VECTOR_FAILED);
                docMapper.updateById(doc);
            }
        }
        Map<String, Object> r = new HashMap<>();
        r.put("processed", processed);
        r.put("failed", failed);
        r.put("vectorized", vectorized);
        r.put("embeddingReady", embedReady);
        return r;
    }

    /** 单文档切片重建；返回切片数。 */
    public int rebuildOne(Long docId) {
        KnowledgeDoc doc = require(docId);
        if (!KnowledgeDoc.STATUS_PUBLISHED.equals(doc.getStatus())) {
            chunkMapper.softDeleteByDocId(docId);
            doc.setChunkCount(0);
            doc.setVectorStatus(KnowledgeDoc.VECTOR_PENDING);
            docMapper.updateById(doc);
            return 0;
        }
        chunkMapper.softDeleteByDocId(docId);
        List<String> pieces = chunk(doc.getContent());
        boolean embedReady = embeddingClient.ready();
        int seq = 0;
        for (String piece : pieces) {
            long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            if (embedReady) {
                try {
                    chunkMapper.insertWithVector(id, docId, seq, piece, embeddingClient.embed(piece));
                } catch (Exception e) {
                    log.warn("[cs] 文档 {} 切片 {} 向量化失败，落词法切片: {}", docId, seq, e.getMessage());
                    chunkMapper.insertLexical(id, docId, seq, piece);
                }
            } else {
                chunkMapper.insertLexical(id, docId, seq, piece);
            }
            seq++;
        }
        doc.setChunkCount(seq);
        doc.setVectorStatus(embedReady ? KnowledgeDoc.VECTOR_DONE : KnowledgeDoc.VECTOR_PENDING);
        docMapper.updateById(doc);
        return seq;
    }

    private List<String> chunk(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<String> pieces = new java.util.ArrayList<>();
        String text = content.trim();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            pieces.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return pieces;
    }

    private static void validate(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "标题必填");
        }
        if (title.length() > 100) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "标题不能超过 100 字");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "内容必填");
        }
        if (content.length() > 20000) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "内容不能超过 20000 字");
        }
    }

    private Map<String, Object> toVo(KnowledgeDoc doc) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", doc.getId());
        m.put("title", doc.getTitle());
        m.put("category", doc.getCategory());
        m.put("status", doc.getStatus());
        m.put("version", doc.getVersion());
        m.put("vectorStatus", doc.getVectorStatus());
        m.put("chunkCount", doc.getChunkCount());
        m.put("contentPreview", doc.getContent() == null ? "" :
                doc.getContent().substring(0, Math.min(120, doc.getContent().length())));
        m.put("updatedAt", doc.getUpdatedAt());
        return m;
    }
}
