package com.shangpivot.customerservice.agent;

import com.shangpivot.customerservice.agent.reference.ChatCardContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 澄清后的下一轮承接：用户只回“1 / 第一个 / 第二个 / 确认 / 好的”时，
 * 从上一轮助手的问题或商品列表里解析出目标商品，再走确定性的加购/规格流程。
 *
 * <p>不新增会话状态表：上一轮助手的提问文本已经落库，按文本解析既能跨重启，
 * 也能兼容多端续聊；同时保留 {@link ProductReferenceResolver} 作为商品名/品牌/品类的兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationFollowUpService {

    private static final Pattern NUMBERED_LINE = Pattern.compile("(?m)^\\s*(\\d+)[.、]\\s*(.+?)\\s*$");
    private static final Pattern PRODUCT_TAG = Pattern.compile("【商品\\d+】([^，,\\n]+)");
    private static final Pattern TITLE = Pattern.compile("《([^》]+)》");
    private static final Pattern ORDINAL = Pattern.compile(
            "^(?:第\\s*)?([1-5一二三四五])\\s*(?:个|款|号|规格)?$");
    private static final Pattern SELECT_ORDINAL = Pattern.compile(
            "^(?:选|要|来|拿|规格)\\s*(?:第\\s*)?([1-5一二三四五])\\s*(?:个|款|号|规格)?$");
    private static final Pattern ANY_ORDINAL = Pattern.compile(
            "(?:规格|选|第)\\s*([1-5一二三四五])\\s*(?:个|款|号)?");
    private static final Pattern AFFIRM = Pattern.compile(
            "^(?:确认|确定|好的|好|行|可以|是|对|嗯|嗯嗯|要|就要|买|就买|就它|就要它|就这个|这个|那就这个|就要这个)"
                    + "(?:吧|了|呀|啊|啦|嘛)?$");

    private final ChatCardContextService cardContextService;

    public Optional<IntentRouter.Intent> resolve(Long sessionId, String userText) {
        String text = clean(userText);
        if (text.isBlank() || sessionId == null) {
            return Optional.empty();
        }
        String last = cardContextService.lastAssistantText(sessionId);
        if (last == null || last.isBlank()) {
            return Optional.empty();
        }

        // 1) 上一轮在问“多个规格选哪个”：序号是 SKU 序号，商品名从《商品》里取。
        if (asksSkuChoice(last)) {
            String product = titleProduct(last);
            Integer index = ordinal(text);
            if (product != null && index != null) {
                log.debug("[cs] follow-up sku choice {} -> {}", index, product);
                return Optional.of(addCart(product, IntentRouter.extractQty(userText)));
            }
            return Optional.empty();
        }

        // 2) 上一轮在让用户选商品（澄清序号 / 找不到新商品 / 推荐列表）：序号是商品序号。
        if (asksProductChoice(last)) {
            List<String> names = choiceNames(last);
            Integer index = ordinal(text);
            if (index != null) {
                if (index >= 1 && index <= names.size()) {
                    log.debug("[cs] follow-up product ordinal {} -> {}", index, names.get(index - 1));
                    return Optional.of(addCart(names.get(index - 1), IntentRouter.extractQty(userText)));
                }
                // 序号越界：交给解析器按原话处理，避免静默选错。
                return Optional.empty();
            }
            if (affirmative(text)) {
                if (names.size() == 1) {
                    return Optional.of(addCart(names.get(0), IntentRouter.extractQty(userText)));
                }
                if (names.size() > 1) {
                    // 多候选项下“确认/好的”不能猜；用空指代让解析器走歧义反问。
                    return Optional.of(addCart("", IntentRouter.extractQty(userText)));
                }
            }
            String named = names.stream().filter(name -> containsName(text, name)).findFirst().orElse(null);
            if (named != null) {
                return Optional.of(addCart(named, IntentRouter.extractQty(userText)));
            }
        }

        // 3) 上一轮在问“您是要加购《X》吗”：是/确认直接落到该商品。
        if (asksProductConfirm(last)) {
            String product = titleProduct(last);
            if (product != null && affirmative(text)) {
                return Optional.of(addCart(product, IntentRouter.extractQty(userText)));
            }
        }
        return Optional.empty();
    }

    /** 上一轮明确在问规格序号（addCartClarify）。 */
    private static boolean asksSkuChoice(String last) {
        return last.contains("有多个规格") && last.contains("【规格");
    }

    /** 上一轮在让用户选商品：澄清序号、旧商品列表、或推荐结果里的【商品N】。 */
    private static boolean asksProductChoice(String last) {
        return last.contains("请回复序号")
                || last.contains("请告诉我要哪一款")
                || last.contains("回复序号或商品名")
                || last.contains("【商品1】")
                || last.contains("【商品2】");
    }

    /** 上一轮在问是/否加购确认。 */
    private static boolean asksProductConfirm(String last) {
        return last.contains("您是要加购") || last.contains("您是要查询规格");
    }

    private static IntentRouter.Intent addCart(String productRef, int quantity) {
        return new IntentRouter.Intent(IntentRouter.Type.ADDCART, "", null,
                productRef == null ? "" : productRef, Math.max(1, quantity), true);
    }

    /** 从“1. 美的 风冷 冰箱”或“【商品1】美的 风冷 冰箱，4599 元”中按顺序取商品名。 */
    private static List<String> choiceNames(String last) {
        Set<String> names = new LinkedHashSet<>();
        if (last.contains("【商品1】") || last.contains("【商品2】")) {
            Matcher tag = PRODUCT_TAG.matcher(last);
            while (tag.find()) {
                String name = cleanName(tag.group(1));
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        } else {
            Matcher line = NUMBERED_LINE.matcher(last);
            while (line.find()) {
                String name = cleanName(line.group(2));
                if (!name.isBlank() && !name.startsWith("以上")) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[，,].*$", "")
                .replaceAll("[（(][^）)]*[）)]\\s*$", "")
                .replaceAll("[。！？!?].*$", "")
                .trim();
    }

    private static String titleProduct(String last) {
        Matcher matcher = TITLE.matcher(last);
        return matcher.find() ? cleanName(matcher.group(1)) : null;
    }

    /** 只接受 1..5 / 一..五，避免把手机号、价格等长数字当序号。 */
    private static Integer ordinal(String text) {
        Matcher anywhere = ANY_ORDINAL.matcher(text);
        if (anywhere.find()) {
            return toIndex(anywhere.group(1));
        }
        Matcher matcher = ORDINAL.matcher(text);
        if (!matcher.matches()) {
            matcher = SELECT_ORDINAL.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
        }
        return toIndex(matcher.group(1));
    }

    private static Integer toIndex(String raw) {
        int index = "一二三四五".indexOf(raw);
        if (index >= 0) {
            return index + 1;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean affirmative(String text) {
        if (AFFIRM.matcher(text).matches()) {
            return true;
        }
        // “确认 就买这个”“好的，就要这个”“行，就它”等确认词 + 指示代词组合
        return text.matches("^(?:确认|确定|好的|好|行|可以|是|对|嗯).*(?:这个|那个|它|这款|那款).*$");
    }

    private static boolean containsName(String text, String name) {
        String n = IntentRouter.normalizeProductText(name);
        String t = IntentRouter.normalizeProductText(text);
        return !n.isBlank() && n.length() >= 2 && t.contains(n);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\s，。！？、：；\"'“”‘’（）【】《》,.!?]", "");
    }
}
