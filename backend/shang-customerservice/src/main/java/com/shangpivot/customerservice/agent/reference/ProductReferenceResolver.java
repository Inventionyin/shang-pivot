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
        return resolve(candidates, userText, productRef, "");
    }

    public Resolution resolve(List<ProductCardDTO> candidates, String userText, String productRef,
                              String lastAssistantText) {
        List<ProductCardDTO> safe = candidates == null ? List.of() : candidates.stream()
                .filter(Objects::nonNull)
                .filter(c -> c.spuId() != null)
                .toList();
        String text = userText == null ? "" : userText.trim();
        String ref = productRef == null ? "" : productRef.trim();

        // 规格快捷选项：“规格1/选2”承接上一轮“《商品》有多个规格”的上下文。
        Matcher skuChoice = Pattern.compile("^(?:规格|选)\\s*([1-5])$").matcher(text);
        if (skuChoice.matches() && lastAssistantText != null && !lastAssistantText.isBlank()) {
            Matcher asked = Pattern.compile("《([^》]+)》").matcher(lastAssistantText);
            if (asked.find()) {
                String askedNorm = normalize(asked.group(1));
                for (ProductCardDTO card : safe) {
                    String nameNorm = normalize(card.name());
                    if (!askedNorm.isBlank() && nameNorm.length() >= 2
                            && (nameNorm.contains(askedNorm) || askedNorm.contains(nameNorm))) {
                        return new Unique(card);
                    }
                }
            }
        }

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

        // 2.1) “有什么规格/有货吗/多少钱”这类短追问：省略了商品名，直接承接上文。
        if (isContextFollowUp(ref, text)) {
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
                int score = score(card, probeNorm, textNorm, text);
                if (score > 0) {
                    scored.add(new Scored(card, score));
                }
            }
            Resolution byScore = choose(scored, probeNorm, safe);
            if (byScore != null) {
                return byScore;
            }
        }

        // 4) 检索兜底：只保留与指代词有真实字面/品类/品牌交集的命中，避免向量检索把“洗碗机”
        //    语义近邻成冰箱/洗衣机，进而把无关商品当成候选。
        final String queryNorm = probeNorm;
        List<RetrievalService.ProductHit> hits = search(probe, text).stream()
                .filter(hit -> relevant(hit.card(), queryNorm, text))
                .toList();
        // 有精确名称命中时，优先精确命中；避免“iPhone 15 Pro 128G”同时被
        // token/相似度命中到 iPhone 15 基础款。
        List<RetrievalService.ProductHit> exactHits = hits.stream()
                .filter(hit -> normalize(hit.card().name()).contains(queryNorm))
                .toList();
        if (!exactHits.isEmpty()) {
            hits = exactHits;
        }
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
            // 明确点名了商品/品类但检索无交集：不能把上文无关卡片当候选。
            // 例如上文是冰箱、用户说“把洗碗机加进购物车”，应如实告知没找到，而不是反问冰箱。
            return new NotFound("no-match");
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

    private int score(ProductCardDTO card, String probeNorm, String textNorm, String rawText) {
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
        // 用户原话里的英文/型号 token（Redmi、K70、Pro、128G）用于解决
        // “Redmi 有什么规格”这类 productRef 为空、但原话明确点名商品的场景。
        score = Math.max(score, tokenScore(rawText, card.name()));
        return score;
    }

    private static int tokenScore(String rawText, String productName) {
        if (rawText == null || rawText.isBlank() || productName == null || productName.isBlank()) {
            return 0;
        }
        String text = rawText.toLowerCase(java.util.Locale.ROOT);
        String[] tokens = productName.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+");
        int total = 0;
        int hit = 0;
        for (String token : tokens) {
            if (token.length() < 2 || token.matches("\\d+")) {
                continue;
            }
            total++;
            if (text.contains(token)) {
                hit++;
            }
        }
        if (hit == 0 || total == 0) {
            return 0;
        }
        return 45 + (int) Math.round(30.0 * hit / total);
    }

    private static boolean relevant(ProductCardDTO card, String probeNorm, String rawText) {
        if (card == null || probeNorm == null || probeNorm.isBlank()) {
            return false;
        }
        String name = normalize(card.name());
        String subtitle = normalize(card.subtitle());
        String category = normalize(card.categoryName());
        if (name.contains(probeNorm) || subtitle.contains(probeNorm)) {
            return true;
        }
        // 型号/数字类指代：允许“Redmi K70 规格”这类带尾词的前缀命中，但要求足够长的公共前缀，
        // 避免 iPhone 15 128G 把 iPhone 15 Pro 也算进来。
        if (probeNorm.matches(".*\\d.*")) {
            if (name.length() >= 4 && probeNorm.startsWith(name)) {
                return true;
            }
            int common = commonPrefix(name, probeNorm);
            return common >= Math.max(4, (int) Math.ceil(probeNorm.length() * 0.85))
                    || tokenScore(rawText, card.name()) >= 70;
        }
        if (!category.isBlank() && (category.contains(probeNorm) || probeNorm.contains(category))) {
            return true;
        }
        if (ProductAliases.matches(probeNorm, name + " " + subtitle)) {
            return true;
        }
        // 非数字指代才允许 bigram 相似度（“美的冰箱”≈“美的 风冷 冰箱”）。
        if (gramRatio(probeNorm, name) >= 0.6) {
            return true;
        }
        return ProductConstraintParser.needsTermFilter(probeNorm)
                && ProductConstraintParser.matchesTerm(probeNorm, card);
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
        if (!ref.isBlank() && !isPronoun(ref) && !implicitCore(ref).isEmpty()) {
            return false;
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return ref.isBlank();
        }
        return t.matches("^(?:我)?(?:现在|现|就|直接)?(?:我)?(?:买|要|下单|拍下)(?:了|吧|呀|啊|啦|这个|这款|它)?[。！!]?$")
                || t.matches("^(?:就|那)?(?:要)?(?:这个|那个|它|这款|那款)(?:吧|了|呀|啊|啦)?[。！!]?$")
                || t.matches("^(?:来|要|拿|加)(?:一|两|二|三|四|五|\\d+)(?:个|台|件|份|套|部|双|只|盒)?[。！!]?$")
                || t.matches(".*(上面那个|上面这款|刚才那个|前面那个|上一个|同款).*")
                || t.matches(".*(?:把|将|给|帮我).{0,8}(?:它|这个|那个|这款|那款).*(?:加|放|添加|加入|加进).*")
                || t.matches("^(它|这个|那个|这款|那款)[。！!]?$")
                // 口语/确认词 + 购买 + 指示代词（“确认 就买这个”“好的，就要这个”“行，就它”）
                || implicitCore(t).isEmpty();
    }

    /**
     * 去掉确认词、购买动词、指示代词和语气词后，若什么都不剩，就是纯指代/购买确认。
     * 只用于短句，避免把“确认收货”“我要买手机”这类带业务对象的句子误判成指代。
     */
    private static String implicitCore(String text) {
        if (text.length() > 24) {
            return "long";
        }
        String core = text.replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]", "");
        core = core.replaceAll("确认|确定|好的|好|行|可以|嗯|对|是|麻烦|请|帮我|给我|那就|就|直接|现在|吧|了|呀|啊|啦|嘛", "");
        core = core.replaceAll("购买|买|要|想|拿|来|下单|拍下|加购|加入|放进|放入|添加", "");
        core = core.replace("上面那个", "").replace("刚才那个", "").replace("这个", "")
                .replace("那个", "").replace("它", "").replace("这款", "").replace("那款", "")
                .replace("同款", "").replace("上面", "").replace("刚才", "").replace("前面", "");
        return core;
    }

    /** “有什么规格/有货吗/多少钱”这类省略主语的短追问，承接上文商品。 */
    private static boolean isContextFollowUp(String ref, String text) {
        if (!ref.isBlank() && !isPronoun(ref)) {
            return false;
        }
        String t = text == null ? "" : text.replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]", "");
        if (t.isBlank() || t.length() > 14) {
            return false;
        }
        if (t.matches(".*[a-zA-Z0-9].*")) {
            return false;
        }
        if (!ProductConstraintParser.categories(t).isEmpty() || !ProductAliases.tokensFor(t).isEmpty()) {
            return false;
        }
        return t.matches(".*(规格|参数|配置|颜色|配色|尺寸|型号|版本|有货|库存|多少钱|价格|怎么样|值得|哪个好).*");
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

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
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
