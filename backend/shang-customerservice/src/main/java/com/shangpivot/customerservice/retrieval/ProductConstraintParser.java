package com.shangpivot.customerservice.retrieval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户原话解析品类与预算硬约束。
 * 品类映射到生产 product_chunk.category_name（不是模糊别名），保证 SQL 过滤可直接使用。
 */
public final class ProductConstraintParser {

    private static final Pattern RANGE = Pattern.compile(
            "(?:预算|价格|价位)?\\s*([0-9]+|[一二两三四五六七八九十百千万]+)\\s*(?:元|块)?\\s*(左右|上下)");
    private static final Pattern MAX = Pattern.compile(
            "([0-9]+|[一二两三四五六七八九十百千万]+)\\s*(?:元|块)?\\s*(?:以内|以下|之内|不超过|内)");
    private static final Pattern BUDGET = Pattern.compile(
            "(?:预算|价格|价位)\\s*(?:在)?\\s*([0-9]+|[一二两三四五六七八九十百千万]+)");
    private static final Pattern PRICE = Pattern.compile(
            "([0-9]+)\\s*(?:元|块)");

    /** 顺序有意义：先长词再短词，避免“扫地机器人”只命中“机器人”。 */
    private record CategoryRule(String marker, String category) {
    }

    private static final List<CategoryRule> CATEGORY_RULES = List.of(
            new CategoryRule("扫地机器人", "家电"),
            new CategoryRule("扫拖机器人", "家电"),
            new CategoryRule("吸尘器", "家电"),
            new CategoryRule("洗衣机", "家电"),
            new CategoryRule("冰箱", "家电"),
            new CategoryRule("蓝牙耳机", "数码配件"),
            new CategoryRule("耳机", "数码配件"),
            new CategoryRule("智能手表", "数码配件"),
            new CategoryRule("手表", "数码配件"),
            new CategoryRule("充电器", "数码配件"),
            new CategoryRule("游戏本", "电脑/办公"),
            new CategoryRule("办公本", "电脑/办公"),
            new CategoryRule("笔记本", "电脑/办公"),
            new CategoryRule("显示器", "电脑/办公"),
            new CategoryRule("电脑", "电脑/办公"),
            new CategoryRule("跑步鞋", "运动户外"),
            new CategoryRule("帐篷", "运动户外"),
            new CategoryRule("手机", "手机"),
            new CategoryRule("收纳盒", "家居收纳"),
            new CategoryRule("台灯", "家居收纳"),
            new CategoryRule("洁面乳", "美妆个护"),
            new CategoryRule("口红", "美妆个护"),
            new CategoryRule("坚果", "食品生鲜"),
            new CategoryRule("咖啡", "食品生鲜"),
            new CategoryRule("自行车", "自行车"),
            new CategoryRule("平板", "平板"),
            new CategoryRule("相机", "相机")
    );

    private ProductConstraintParser() {
    }

    public static ProductConstraints parse(String text) {
        if (text == null || text.isBlank()) {
            return ProductConstraints.none();
        }
        List<String> categories = categories(text);
        Budget budget = budget(text);
        boolean explicitNewQuery = !categories.isEmpty();
        return new ProductConstraints(categories, budget.min, budget.max, budget.label, explicitNewQuery);
    }

    /**
     * 大类目（数码配件/家电）需要二级词过滤，避免“耳机”召回 Apple Watch、“吸尘器”召回冰箱。
     * 返回 true 时 RetrievalService 会扩大召回窗口并按商品名/副标题过滤。
     */
    public static boolean needsTermFilter(String query) {
        if (query == null) {
            return false;
        }
        return query.contains("耳机") || query.contains("手表") || query.contains("吸尘器")
                || query.contains("扫地") || query.contains("扫拖") || query.contains("洗衣机")
                || query.contains("冰箱") || query.contains("帐篷") || query.contains("跑步鞋")
                || query.contains("充电器") || query.contains("显示器");
    }

    public static boolean matchesTerm(String query, com.shangpivot.commerce.api.ProductCardDTO card) {
        if (query == null || card == null || !needsTermFilter(query)) {
            return true;
        }
        String text = ((card.name() == null ? "" : card.name()) + " "
                + (card.subtitle() == null ? "" : card.subtitle())).toLowerCase(java.util.Locale.ROOT);
        if (query.contains("耳机")) {
            return containsAny(text, "耳机", "airpods", "wh-1000", "降噪", "入耳", "头戴");
        }
        if (query.contains("手表")) {
            return containsAny(text, "手表");
        }
        if (query.contains("吸尘器")) {
            return containsAny(text, "吸尘器", "dyson", "戴森");
        }
        if (query.contains("扫地") || query.contains("扫拖")) {
            return containsAny(text, "扫地", "扫拖", "机器人");
        }
        if (query.contains("洗衣机")) {
            return containsAny(text, "洗衣机");
        }
        if (query.contains("冰箱")) {
            return containsAny(text, "冰箱");
        }
        if (query.contains("帐篷")) {
            return containsAny(text, "帐篷");
        }
        if (query.contains("跑步鞋")) {
            return containsAny(text, "跑步鞋");
        }
        if (query.contains("充电器")) {
            return containsAny(text, "充电器");
        }
        if (query.contains("显示器")) {
            return containsAny(text, "显示器");
        }
        return true;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    static List<String> categories(String text) {
        Set<String> result = new LinkedHashSet<>();
        // “手机号/手机号码”不是商品品类，避免把 PII 咨询误判成手机导购
        String normalized = text.replace("手机号码", " ").replace("手机号", " ");
        for (CategoryRule rule : CATEGORY_RULES) {
            if (normalized.contains(rule.marker())) {
                result.add(rule.category());
            }
        }
        // 同一句话里“手机”与“耳机”是并列需求；保留顺序去重
        return new ArrayList<>(result);
    }

    private record Budget(BigDecimal min, BigDecimal max, String label) {
        static Budget none() {
            return new Budget(null, null, "");
        }
    }

    static Budget budget(String text) {
        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            BigDecimal target = number(range.group(1));
            if (target != null) {
                BigDecimal min = target.multiply(new BigDecimal("0.75")).setScale(0, java.math.RoundingMode.HALF_UP);
                BigDecimal max = target.multiply(new BigDecimal("1.25")).setScale(0, java.math.RoundingMode.HALF_UP);
                return new Budget(min, max, target.setScale(0, java.math.RoundingMode.HALF_UP) + " 元左右");
            }
        }
        Matcher maxMatcher = MAX.matcher(text);
        if (maxMatcher.find()) {
            BigDecimal max = number(maxMatcher.group(1));
            if (max != null) {
                return new Budget(null, max, max.setScale(0, java.math.RoundingMode.HALF_UP) + " 元以内");
            }
        }
        Matcher budget = BUDGET.matcher(text);
        if (budget.find()) {
            BigDecimal target = number(budget.group(1));
            if (target != null) {
                BigDecimal min = target.multiply(new BigDecimal("0.75")).setScale(0, java.math.RoundingMode.HALF_UP);
                BigDecimal max = target.multiply(new BigDecimal("1.25")).setScale(0, java.math.RoundingMode.HALF_UP);
                return new Budget(min, max, target.setScale(0, java.math.RoundingMode.HALF_UP) + " 元左右");
            }
        }
        Matcher price = PRICE.matcher(text);
        if (price.find()) {
            BigDecimal target = number(price.group(1));
            if (target != null) {
                BigDecimal min = target.multiply(new BigDecimal("0.9")).setScale(0, java.math.RoundingMode.HALF_UP);
                BigDecimal max = target.multiply(new BigDecimal("1.1")).setScale(0, java.math.RoundingMode.HALF_UP);
                return new Budget(min, max, target.setScale(0, java.math.RoundingMode.HALF_UP) + " 元左右");
            }
        }
        return Budget.none();
    }

    /** 阿拉伯数字或中文数字；“三千五”“两千五”按口语补足 100。 */
    static BigDecimal number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return new BigDecimal(raw);
        }
        long total = 0;
        long section = 0;
        long number = 0;
        int lastUnit = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int digit = switch (c) {
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                number = digit;
                continue;
            }
            if (c == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
                lastUnit = 10;
            } else if (c == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
                lastUnit = 100;
            } else if (c == '千') {
                section += (number == 0 ? 1 : number) * 1000;
                number = 0;
                lastUnit = 1000;
            } else if (c == '万') {
                total += (section + number) * 10000;
                section = 0;
                number = 0;
                lastUnit = 10000;
            }
        }
        if (number > 0 && lastUnit == 1000) {
            section += number * 100; // 三千五 = 3500
        } else if (number > 0 && lastUnit == 100) {
            section += number * 10;  // 一百五 = 150
        } else {
            section += number;
        }
        long value = total + section;
        return value > 0 ? BigDecimal.valueOf(value) : null;
    }
}
