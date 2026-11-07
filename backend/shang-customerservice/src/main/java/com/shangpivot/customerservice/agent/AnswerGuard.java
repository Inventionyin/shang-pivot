package com.shangpivot.customerservice.agent;

import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.agent.graph.SupervisorAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 完整回答事实校验：商品实体必须来自 cards、禁止身份泄漏/外部平台、订单与购物车事实一致。
 * 任一失败回退后端 displayAnswer（不得把未经校验的模型输出发给用户）。
 */
@Component
public class AnswerGuard {

    public record Result(String answer, boolean replaced, String reason) {
    }

    private static final Pattern IDENTITY = Pattern.compile(
            "(?i)(Claude|Anthropic|DeepSeek|OpenAI|ChatGPT|GPT-?[0-9]|Gemini|通义千问|文心一言|豆包)");
    private static final Pattern EXTERNAL_PLATFORM = Pattern.compile(
            "京东|淘宝|天猫|拼多多|唯品会|苏宁易购|抖音商城");
    private static final Pattern CART_QTY = Pattern.compile("购物车[^。；;\\n]{0,16}?\\d+\\s*(台|件|个|套)");
    private static final Pattern NO_RESULT_CLAIM = Pattern.compile("为您找到|推荐【|推荐以下商品|以下是.*商品");
    private static final Pattern BRAND = Pattern.compile(
            "(?i)(Redmi|iPhone|Xiaomi|HUAWEI|OPPO|vivo|荣耀|索尼|AirPods|戴森|石头|MacBook|ThinkPad|ROG|拯救者|Dell|美的|海尔|Anker|Apple Watch|iQOO|一加|realme|红米|华为|小米|苹果)");
    private static final Pattern TITLE = Pattern.compile("《([^》]+)》");

    @Value("${agent.answer-guard-enabled:true}")
    private boolean enabled = true;

    public Result guard(AgentName agent, SupervisorAgent.Action action, String facts,
                        List<ProductCardDTO> cards, String displayAnswer, String rawAnswer) {
        if (!enabled) {
            return new Result(rawAnswer, false, "guard disabled");
        }
        String answer = rawAnswer == null ? "" : rawAnswer.trim();
        if (answer.isBlank()) {
            return fallback(displayAnswer, "empty answer");
        }
        if (IDENTITY.matcher(answer).find()) {
            return fallback(displayAnswer, "identity leakage");
        }
        if (EXTERNAL_PLATFORM.matcher(answer).find()) {
            return fallback(displayAnswer, "external platform");
        }
        boolean productAction = action == SupervisorAgent.Action.SEARCH_PRODUCTS
                || action == SupervisorAgent.Action.GET_STOCK
                || action == SupervisorAgent.Action.ADD_TO_CART;
        if (productAction) {
            String mismatch = productMismatch(answer, cards);
            if (mismatch != null) {
                return fallback(displayAnswer, mismatch);
            }
        }
        if ((cards == null || cards.isEmpty()) && NO_RESULT_CLAIM.matcher(answer).find()) {
            return fallback(displayAnswer, "claims products with empty cards");
        }
        if (action == SupervisorAgent.Action.GET_ORDER && facts != null && facts.contains("没有订单")
                && CART_QTY.matcher(answer).find()) {
            return fallback(displayAnswer, "invents cart quantity for no-order user");
        }
        if (action == SupervisorAgent.Action.ADD_TO_CART && facts != null
                && facts.contains("已成功为用户加入购物车") && !answer.contains("购物车")) {
            return fallback(displayAnswer, "omits cart fact");
        }
        if ((agent == AgentName.KNOWLEDGE || agent == AgentName.AFTER_SALE)
                && facts != null && !facts.contains("无匹配知识") && !citesKnowledge(answer, facts)) {
            return fallback(displayAnswer, "does not cite knowledge facts");
        }
        return new Result(answer, false, "ok");
    }

    private Result fallback(String displayAnswer, String reason) {
        if (displayAnswer != null && !displayAnswer.isBlank()) {
            return new Result(displayAnswer, true, reason);
        }
        return new Result(FactAnswerRenderer.chitchatFallback(), true, reason);
    }

    /** 返回失败原因；null 表示通过。 */
    private String productMismatch(String answer, List<ProductCardDTO> cards) {
        List<String> names = new ArrayList<>();
        if (cards != null) {
            cards.forEach(card -> names.add(card.name() == null ? "" : card.name().toLowerCase(Locale.ROOT)));
        }
        Matcher matcher = BRAND.matcher(answer);
        while (matcher.find()) {
            String brand = matcher.group(1);
            String normalized = normalizeBrand(brand);
            boolean brandInCards = names.stream().anyMatch(name -> name.contains(normalized)
                    || name.contains(brand.toLowerCase(Locale.ROOT)));
            if (!brandInCards) {
                return "product not in cards: " + brand;
            }
            String tail = answer.substring(matcher.end()).trim();
            Matcher model = Pattern.compile("^([A-Za-z0-9]+)").matcher(tail);
            if (model.find()) {
                String token = model.group(1).toLowerCase(Locale.ROOT);
                if (token.length() >= 2 && names.stream().noneMatch(name -> name.contains(token))) {
                    return "product model not in cards: " + brand + " " + model.group(1);
                }
            }
        }
        return null;
    }

    private static String normalizeBrand(String brand) {
        return switch (brand) {
            case "红米" -> "redmi";
            case "华为" -> "huawei";
            case "小米" -> "xiaomi";
            case "苹果" -> "iphone";
            default -> brand.toLowerCase(Locale.ROOT);
        };
    }

    private static boolean citesKnowledge(String answer, String facts) {
        Matcher title = TITLE.matcher(facts);
        List<String> titles = new ArrayList<>();
        while (title.find()) {
            titles.add(title.group(1));
        }
        if (!titles.isEmpty()) {
            return titles.stream().anyMatch(answer::contains);
        }
        for (String keyword : List.of("7 天", "7天", "15 天", "15天", "不支持", "原路", "寄回", "申请售后", "运费")) {
            if (facts.contains(keyword) && answer.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
