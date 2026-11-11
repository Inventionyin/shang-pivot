package com.shangpivot.customerservice.retrieval;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 商品品牌/系列的检索别名（索引层）。
 *
 * <p>名称里常是英文/系列名（HUAWEI、Xiaomi、iPhone、Dell），用户常搜中文（华为、小米、苹果、戴尔）。
 * 索引服务把中文别名写入 product_chunk.alias_text，词法/向量检索即可命中。</p>
 */
public final class ProductSearchAliases {

    private record Rule(String token, String aliases) {
    }

    private static final Rule[] RULES = {
            new Rule("huawei", "华为 HUAWEI"),
            new Rule("xiaomi", "小米 Xiaomi"),
            new Rule("redmi", "红米 Redmi"),
            new Rule("iphone", "苹果 iPhone"),
            new Rule("macbook", "苹果 MacBook"),
            new Rule("airpods", "苹果 AirPods"),
            new Rule("apple watch", "苹果 AppleWatch 智能手表"),
            new Rule("dell", "戴尔 Dell"),
            new Rule("thinkpad", "联想 ThinkPad"),
            new Rule("lenovo", "联想 Lenovo"),
            new Rule("拯救者", "联想 拯救者"),
            new Rule("sony", "索尼 Sony"),
            new Rule("dyson", "戴森 Dyson"),
            new Rule("anker", "安克 Anker"),
            new Rule("haier", "海尔 Haier"),
            new Rule("midea", "美的 Midea"),
            new Rule("roborock", "石头 Roborock 扫拖机器人"),
            new Rule("oppo", "OPPO"),
            new Rule("vivo", "vivo"),
            new Rule("honor", "荣耀 HONOR"),
            new Rule("罗技", "罗技 Logitech"),
    };

    private ProductSearchAliases() {
    }

    /** 根据商品名称生成中文/英文别名文本；没有命中返回空串。 */
    public static String forProductName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Set<String> aliases = new LinkedHashSet<>();
        for (Rule rule : RULES) {
            if (lower.contains(rule.token())) {
                for (String alias : rule.aliases().split("\\s+")) {
                    if (!alias.isBlank()) {
                        aliases.add(alias);
                    }
                }
            }
        }
        return String.join(" ", aliases);
    }

    /** 查询扩展：用户搜“华为手机”时补 HUAWEI，让检索 SQL 命中英文商品名。 */
    public static String expandQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        terms.add(query);
        for (Rule rule : RULES) {
            for (String alias : rule.aliases().split("\\s+")) {
                if (alias.length() >= 2 && lower.contains(alias.toLowerCase(Locale.ROOT))) {
                    for (String token : rule.token().split("\\s+")) {
                        terms.add(token);
                    }
                }
            }
        }
        return String.join(" ", terms);
    }
}
