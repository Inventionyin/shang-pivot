package com.shangpivot.customerservice.agent.reference;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品品牌/系列的中英文别名。
 *
 * <p>只用于“上文商品指代”解析，不参与商品检索排序。V3.4.3 先以静态表覆盖当前 30 个在售 SPU；
 * 后续可在 product_chunk 增加 brand_name 后迁到索引层。</p>
 */
public final class ProductAliases {

    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("华为", List.of("huawei")),
            Map.entry("小米", List.of("xiaomi")),
            Map.entry("红米", List.of("redmi")),
            Map.entry("苹果", List.of("iphone", "macbook", "airpods", "apple watch", "apple")),
            Map.entry("戴尔", List.of("dell")),
            Map.entry("联想", List.of("thinkpad", "lenovo", "拯救者")),
            Map.entry("索尼", List.of("sony")),
            Map.entry("戴森", List.of("dyson")),
            Map.entry("安克", List.of("anker")),
            Map.entry("荣耀", List.of("honor")),
            Map.entry("惠普", List.of("hp")),
            Map.entry("华硕", List.of("asus")),
            Map.entry("宏碁", List.of("acer")),
            Map.entry("三星", List.of("samsung")),
            Map.entry("谷歌", List.of("google", "pixel"))
    );

    private ProductAliases() {
    }

    /** 指代词是否命中某个品牌的别名，且商品文本包含该品牌的英文/系列词。 */
    public static boolean matches(String ref, String productText) {
        if (ref == null || ref.isBlank() || productText == null || productText.isBlank()) {
            return false;
        }
        String refLower = ref.toLowerCase(Locale.ROOT);
        String productLower = productText.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : ALIASES.entrySet()) {
            if (!refLower.contains(e.getKey())) {
                continue;
            }
            if (productLower.contains(e.getKey())) {
                return true;
            }
            for (String token : e.getValue()) {
                if (productLower.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 返回指代词命中的别名 token（用于日志/调试）。 */
    public static List<String> tokensFor(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        String refLower = ref.toLowerCase(Locale.ROOT);
        return ALIASES.entrySet().stream()
                .filter(e -> refLower.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }
}
