package com.shangpivot.customerservice.controller;

import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import com.shangpivot.customerservice.entity.KnowledgeDoc;
import com.shangpivot.customerservice.service.KnowledgeAdminService;
import com.shangpivot.customerservice.service.ProductIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 知识库 + 商品索引重建（/admin/customer-service/**）。 */
@Tag(name = "管理端-知识库")
@RestController
@RequestMapping("/admin/customer-service")
@RequiredArgsConstructor
public class CsKnowledgeController {

    private final KnowledgeAdminService knowledgeAdminService;
    private final ProductIndexService productIndexService;

    public record DocRequest(String title, String category, String content, String status) {
    }

    @Operation(summary = "知识文档分页")
    @GetMapping("/knowledge-docs")
    public Result<?> page(@RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String status,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size) {
        return Result.ok(knowledgeAdminService.page(keyword, status, page, Math.min(size, 50)));
    }

    @Operation(summary = "知识文档详情")
    @GetMapping("/knowledge-docs/{id}")
    public Result<KnowledgeDoc> detail(@PathVariable Long id) {
        return Result.ok(knowledgeAdminService.require(id));
    }

    @Operation(summary = "新建知识文档")
    @PostMapping("/knowledge-docs")
    @OpLog(module = "cs-knowledge", action = "create")
    public Result<KnowledgeDoc> create(@RequestBody DocRequest req) {
        boolean publish = "PUBLISHED".equals(req.status());
        return Result.ok(knowledgeAdminService.create(req.title(), req.category(), req.content(), publish));
    }

    @Operation(summary = "更新知识文档")
    @PutMapping("/knowledge-docs/{id}")
    @OpLog(module = "cs-knowledge", action = "update")
    public Result<KnowledgeDoc> update(@PathVariable Long id, @RequestBody DocRequest req) {
        return Result.ok(knowledgeAdminService.update(id, req.title(), req.category(), req.content(), req.status()));
    }

    @Operation(summary = "删除知识文档")
    @DeleteMapping("/knowledge-docs/{id}")
    @OpLog(module = "cs-knowledge", action = "delete")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeAdminService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "重建向量索引", description = "docId 传单篇；full=全量；否则仅待向量化")
    @PostMapping("/knowledge-docs/rebuild")
    @OpLog(module = "cs-knowledge", action = "rebuild")
    public Result<Map<String, Object>> rebuild(@RequestBody(required = false) Map<String, Object> req) {
        Long docId = null;
        boolean full = false;
        if (req != null) {
            Object d = req.get("docId");
            if (d instanceof Number n) {
                docId = n.longValue();
            }
            full = Boolean.TRUE.equals(req.get("full"));
        }
        return Result.ok(knowledgeAdminService.rebuild(docId, full));
    }

    @Operation(summary = "商品索引全量重建")
    @PostMapping("/product-index/rebuild")
    @OpLog(module = "cs-knowledge", action = "rebuild-product")
    public Result<Map<String, Object>> rebuildProductIndex() {
        return Result.ok(productIndexService.rebuildFull());
    }
}
