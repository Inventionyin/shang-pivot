package com.shangpivot.customerservice.agent.reference;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.retrieval.ProductConstraintParser;
import com.shangpivot.customerservice.retrieval.ProductConstraints;
import com.shangpivot.customerservice.retrieval.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上文商品指代解析：把“现在买 / 把手机加进购物车 / 第二个 / 它”解析成唯一的商品。
 *
 * <p>设计原则：候选打分 + 歧义反问，绝不按列表顺序取第一个。graph 与 legacy 共用本组件，
 * 避免两套引擎对同一句话给出不同商品。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductReferenceResolver {

    private static final int MIN_SCORE = 40;
    private static final int MAX_CLARIFY = 5;
    private static final Pattern ORDINAL = Pattern.compile("第\\s*([一二三四五1-5])");

    private final RetrievalService retrievalService;

    public sealed interface Resolution permits Unique, Ambiguous, NotFound {
    }

    public record Unique(ProductCardDTO product) implements Resolution {
    }

    public record Ambiguous(List<ProductCardDTO> candidates) implements Resolution {
    }

    public record NotFound(String reason) implements Resolution {
    }

    /**
     * @param candidates 最近若干条商品卡消息合并后的候选（按推荐时间倒序去重）
     * @param userText   用户本轮原话
     * @param productRef 意图层抽取的商品指代，可为空
     */
    public Resolution resolve(List<ProductCardDTO> candidates, String userText, String productRef) {
        List<ProductCardDTO> safe = candidates == null ? List.of() : candidates.stream()
                .filter(Objects::nonNull)
                .filter(c -> c.spuId() != null)
                .toList();
        String text = userText == null ? "" : userText.trim();
        String ref = productRef == null ? "" : productRef.trim();

        // 1) 序号指代：第一个/第二个/第2款
        Matcher ordinal = ORDINAL.matcher(ref + " " + text);
        if (ordinal.find()) {
            if (safe.isEmpty()) {
                return new NotFound("no-context");
            }
            int index = ordinalIndex(ordinal.group(1));
            if (index >= 0 && index < safe.size()) {
                return new Unique(safe.get(index));
            }
            return new Ambiguous(safe.subList(0, Math.min(MAX_CLARIFY, safe.size())));
        }

        // 2) 纯指代/购买确认短句：上文唯一才执行，多张卡必须反问
        if (isImplicitReference(ref, text)) {
            if (safe.size() == 1) {
                return new Unique(safe.get(0));
            }
            if (safe.size() > 1) {
                return new Ambiguous(safe.subList(0, Math.min(MAX_CLARIFY, safe.size())));
            }
            return new NotFound("no-context");
        }

        String probe = ref.isBlank() ? IntentRouter.extractKeyword(text) : ref;
        String probeNorm = normalize(probe);
        String textNorm = normalize(text);
        if (probeNorm.isBlank()) {
            probeNorm = textNorm;
        }

        // 3) 候选打分
        if (!probeNorm.isBlank() && !safe.isEmpty()) {
            List<Scored> scored = new ArrayList<>();
            for (ProductCardDTO card : safe) {
                int score = score(card, probeNorm, textNorm);
                if (score > 0) {
                    scored.add(new Scored(card, score));
                }
            }
            Resolution byScore = choose(scored, probeNorm, safe);
            if (byScore != null) {
                return byScore;
            }
        }

        // 4) 检索兜底：与上文候选取交集；无上文时才允许从目录重新定位
        List<RetrievalService.ProductHit> hits = search(probe, text);
        if (!safe.isEmpty()) {
            List<ProductCardDTO> inter = new ArrayList<>();
            for (RetrievalService.ProductHit hit : hits) {
                if (safe.stream().anyMatch(c -> Objects.equals(c.spuId(), hit.card().spuId()))) {
                    inter.add(hit.card());
                }
            }
            if (inter.size() == 1) {
                return new Unique(inter.get(0));
            }
            if (inter.size() > 1) {
                return new Ambiguous(distinct(inter));
            }
            // 明确点名了目录商品但不在上文：允许按精确名定位一次
            if (!isPronoun(ref) && !isCategoryOrBrand(ref) && hits.size() == 1) {
                return new Unique(hits.get(0).card());
            }
            return new Ambiguous(safe.subList(0, Math.min(MAX_CLARIFY, safe.size())));
        }

        if (hits.size() == 1) {
            return new Unique(hits.get(0).card());
        }
        if (hits.size() > 1) {
            return new Ambiguous(distinct(hits.stream().map(RetrievalService.ProductHit::card).toList()));
        }
        return new NotFound("no-match");
    }

    /** 返回 null 表示没有达到阈值的候选，交给检索兜底。 */
    private Resolution choose(List<Scored> scored, String probeNorm, List<ProductCardDTO> safe) {
        if (scored.isEmpty()) {
            return null;
        }
        int top = scored.stream().mapToInt(Scored::score).max().orElse(0);
        if (top < MIN_SCORE) {
            return null;
        }
        List<ProductCardDTO> topCards = distinct(scored.stream()
                .filter(s -> s.score() == top)
                .map(Scored::card)
                .toList());
        if (topCards.size() == 1) {
            return new Unique(topCards.get(0));
        }
        // “iPhone 15”同时命中 iPhone 15 128G / iPhone 15 Pro 128G：优先剩余部分只是规格的基础款
        List<ProductCardDTO> specOnly = topCards.stream()
                .filter(c -> isSpecOnlyRemainder(normalize(c.name()), probeNorm))
                .toList();
        if (specOnly.size() == 1) {
            return new Unique(specOnly.get(0));
        }
        return new Ambiguous(topCards.subList(0, Math.min(MAX_CLARIFY, topCards.size())));
    }

    private int score(ProductCardDTO card, String probeNorm, String textNorm) {
        String name = normalize(card.name());
        String subtitle = normalize(card.subtitle());
        String category = normalize(card.categoryName());
        String productText = name + " " + subtitle;

        // 品牌别名优先：避免“联想”只命中“联想 拯救者”而漏掉 ThinkPad
        if (!ProductAliases.tokensFor(probeNorm).isEmpty()) {
            return ProductAliases.matches(probeNorm, productText) ? 60 : 0;
        }

        // 品类词：用 categoryName 收窄，而不是要求商品名里含品类字
        if (!ProductConstraintParser.categories(probeNorm).isEmpty()) {
            int score = 0;
            if (!category.isBlank() && (category.contains(probeNorm) || probeNorm.contains(category))) {
                score = 50;
            }
            if (ProductConstraintParser.needsTermFilter(probeNorm)
                    && ProductConstraintParser.matchesTerm(probeNorm, card)) {
                score = Math.max(score, 55);
            }
            // 名称直接含品类词（冰箱/洗衣机/吸尘器/帐篷等）仍是更强信号
            if (name.contains(probeNorm) && probeNorm.length() >= 2) {
                score = Math.max(score, 80);
            }
            return score;
        }

        int score = 0;
        if (name.equals(probeNorm)) {
            score = 100;
        } else if (name.startsWith(probeNorm) && probeNorm.length() >= 2) {
            score = 85;
        } else if (name.contains(probeNorm) && probeNorm.length() >= 2) {
            score = 80;
        } else if (subtitle.contains(probeNorm) && probeNorm.length() >= 2) {
            score = 50;
        } else if (probeNorm.length() >= 2 && gramRatio(probeNorm, name) >= 0.6) {
            score = 60;
        }
        if (textNorm.contains(name) && name.length() >= 2) {
            score = Math.max(score, 90);
        }
        return score;
    }

    private List<RetrievalService.ProductHit> search(String probe, String text) {
        String keyword = probe == null ? "" : probe.trim();
        if (keyword.isBlank()) {
            keyword = IntentRouter.extractKeyword(text);
        }
        if (keyword.isBlank()) {
            return List.of();
        }
        try {
            ProductConstraints constraints = ProductConstraintParser.parse(text);
            return retrievalService.searchProducts(keyword, 10, constraints);
        } catch (Exception e) {
            log.debug("[cs] reference search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isImplicitReference(String ref, String text) {
        if (!ref.isBlank() && !isPronoun(ref)) {
            return false;
        }
        String t = text == null ? "" : text.trim();
        return t.matches("^(?:我)?(?:现在|现|就|直接)?(?:我)?(?:买|要|下单|拍下)(?:了|吧|呀|啊|啦|这个|这款|它)?[。！!]?$")
                || t.matches("^(?:就|那)?(?:要)?(?:这个|那个|它|这款|那款)(?:吧|了|呀|啊|啦)?[。！!]?$")
                || t.matches("^(?:来|要|拿|加)(?:一|两|二|三|四|五|\\d+)(?:个|台|件|份|套|部|双|只|盒)?[。！!]?$")
                || t.matches(".*(上面那个|上面这款|刚才那个|前面那个|上一个|同款).*")
                || t.matches(".*(?:把|将|给|帮我).{0,8}(?:它|这个|那个|这款|那款).*(?:加|放|添加|加入|加进).*")
                || t.matches("^(它|这个|那个|这款|那款)[。！!]?$");
    }

    private static boolean isPronoun(String ref) {
        if (ref == null || ref.isBlank()) {
            return true;
        }
        return ref.matches("^(它|这个|那个|这款|那款|同款|上面那个|上面这款|上面|刚才那个|刚才|前面那个|前面|上一个)$")
                || ref.matches(".*(它|这个|那个|上面|刚才|前面|同款).*");
    }

    private static boolean isCategoryOrBrand(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        return !ProductConstraintParser.categories(ref).isEmpty()
                || !ProductAliases.tokensFor(ref).isEmpty();
    }

    private static int ordinalIndex(String raw) {
        int n = "一二三四五".indexOf(raw);
        if (n >= 0) {
            return n;
        }
        try {
            return Integer.parseInt(raw) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** “iPhone 15”命中“iPhone 15 128G”时，剩余部分应以数字/容量开头，而不是 Pro/Plus/Max。 */
    private static boolean isSpecOnlyRemainder(String nameNorm, String probeNorm) {
        if (!nameNorm.startsWith(probeNorm) || nameNorm.equals(probeNorm)) {
            return false;
        }
        String remainder = nameNorm.substring(probeNorm.length());
        return remainder.matches("^[0-9].*")
                || remainder.matches("^(?:g|gb|t|tb|ml|l|kg|寸|码|袋|片|粒|支|瓶|盒|套|双|只).*");
    }

    private static double gramRatio(String probeNorm, String nameNorm) {
        if (probeNorm.length() < 2 || nameNorm.isEmpty()) {
            return 0;
        }
        int total = 0;
        int hit = 0;
        for (int i = 0; i + 2 <= probeNorm.length(); i++) {
            total++;
            if (nameNorm.contains(probeNorm.substring(i, i + 2))) {
                hit++;
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    private static String normalize(String value) {
        return IntentRouter.normalizeProductText(value);
    }

    private static List<ProductCardDTO> distinct(List<ProductCardDTO> cards) {
        List<ProductCardDTO> result = new ArrayList<>();
        for (ProductCardDTO card : cards) {
            if (result.stream().noneMatch(c -> Objects.equals(c.spuId(), card.spuId()))) {
                result.add(card);
            }
        }
        return result;
    }

    private record Scored(ProductCardDTO card, int score) {
    }
}
