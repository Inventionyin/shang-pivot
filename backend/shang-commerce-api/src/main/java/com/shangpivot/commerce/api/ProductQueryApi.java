package com.shangpivot.commerce.api;

import java.util.Collection;
import java.util.List;

/**
 * Cross-context product query API. Implemented by {@code shang-commerce}.
 * 供智能客服做商品检索与索引快照，禁止直读 commerce 表。
 */
public interface ProductQueryApi {

    /** 按关键词检索在售商品卡片（名称/副标题 LIKE，销量排序）。 */
    List<ProductCardDTO> search(String keyword, int limit);

    /** 按 SPU ID 批量取在售商品卡片；下架/缺失不返回。 */
    List<ProductCardDTO> getBySpuIds(Collection<Long> spuIds);

    /** 在售商品索引快照（增量游标：id > lastSpuId，按 id 升序）。 */
    List<ProductSnapshotDTO> snapshotPage(long lastSpuId, int limit);

    /** 在售商品总数（索引进度展示用）。 */
    long countOnSale();
}
