package com.shangpivot.customerservice.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.customerservice.entity.ProductAlias;
import com.shangpivot.customerservice.mapper.ProductAliasMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 品牌/系列别名（配置表驱动，60 秒缓存）。
 *
 * <p>表 customerservice.product_alias：alias=用户叫法，target_token=商品名中的英文/系列词。
 * 运营新增一行即可扩展，不需要改代码；索引重建后 alias_text 生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAliasService {

    private static final long CACHE_MILLIS = 60_000L;

    private final ProductAliasMapper mapper;
    private volatile List<ProductAlias> cache = List.of();
    private volatile long loadedAt = 0L;

    /** 根据商品名生成索引别名文本（如 HUAWEI Mate X5 → 华为）。 */
    public String aliasTextFor(String productName) {
        if (productName == null || productName.isBlank()) {
            return "";
        }
        String lower = productName.toLowerCase(Locale.ROOT);
        Set<String> aliases = new LinkedHashSet<>();
        for (ProductAlias row : aliases()) {
            String target = row.getTargetToken();
            if (target != null && !target.isBlank() && lower.contains(target.toLowerCase(Locale.ROOT))) {
                aliases.add(row.getAlias());
            }
        }
        return String.join(" ", aliases);
    }

    /** 查询扩展：用户搜“华为手机”时补 huawei。 */
    public String expandQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        terms.add(query);
        for (ProductAlias row : aliases()) {
            String alias = row.getAlias();
            String target = row.getTargetToken();
            if (alias != null && !alias.isBlank() && lower.contains(alias.toLowerCase(Locale.ROOT))
                    && target != null && !target.isBlank()) {
                terms.add(target);
            }
        }
        return String.join(" ", terms);
    }

    /** 管理端/测试可主动失效缓存。 */
    public void refresh() {
        loadedAt = 0L;
    }

    private List<ProductAlias> aliases() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < CACHE_MILLIS && !cache.isEmpty()) {
            return cache;
        }
        try {
            cache = mapper.selectList(new LambdaQueryWrapper<ProductAlias>()
                    .eq(ProductAlias::getEnabled, true)
                    .orderByAsc(ProductAlias::getId));
            loadedAt = now;
        } catch (Exception e) {
            log.warn("[cs] 读取商品别名失败，沿用旧缓存: {}", e.getMessage());
        }
        return cache;
    }
}
