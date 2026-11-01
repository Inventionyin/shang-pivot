package com.shangpivot.commerce.api;

import java.util.List;

/**
 * Cross-context SKU query API (V3.0 智能客服规格问答用)。Implemented by {@code shang-commerce}.
 */
public interface ProductSkuApi {

    /** 在售 SPU 的启用 SKU 列表（含实时可售库存）。 */
    List<ProductSkuDTO> listEnabledBySpu(Long spuId);
}
