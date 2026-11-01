package com.shangpivot.customerservice.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 中文词法检索的查询预处理：CJK bigram + ASCII 词元（见 ADR-0006 / 数据模型 §3）。
 */
public final class TextGrams {

    private TextGrams() {
    }

    /** 抽取查询词元：相邻两字 bigram 去重 + 连续 ASCII 词；全句单字时退化为整句与单字。 */
    public static List<String> of(String query, int maxGrams) {
        Set<String> grams = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        StringBuilder ascii = new StringBuilder();
        List<Character> cjk = new ArrayList<>();
        flushAscii(ascii, grams);
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (isAsciiWord(c)) {
                ascii.append(c);
            } else {
                flushAscii(ascii, grams);
                if (c > 127) {
                    cjk.add(c);
                }
            }
        }
        flushAscii(ascii, grams);
        for (int i = 0; i + 1 < cjk.size(); i++) {
            grams.add("" + cjk.get(i) + cjk.get(i + 1));
        }
        if (grams.isEmpty() && !cjk.isEmpty()) {
            grams.add("" + cjk.get(0));
        }
        return grams.stream().limit(Math.max(1, maxGrams)).toList();
    }

    private static void flushAscii(StringBuilder ascii, Set<String> grams) {
        if (!ascii.isEmpty()) {
            grams.add(ascii.toString().toLowerCase(Locale.ROOT));
            ascii.setLength(0);
        }
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
