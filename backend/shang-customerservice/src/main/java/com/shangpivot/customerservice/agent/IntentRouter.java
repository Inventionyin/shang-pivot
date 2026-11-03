package com.shangpivot.customerservice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.customerservice.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图识别（两阶段编排的阶段一，ADR-0005）：LLM 输出 JSON，失败回落关键词规则。
 * V3.0 对抗性审查增量：新增 SPECS（规格/库存追问）与 ADDCART（对话内加购）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    public enum Type {PRESALE, ORDER, AFTERSALE, KNOWLEDGE, CHITCHAT, SPECS, ADDCART}

    public record Intent(Type type, String keyword, String orderNo, String productRef, int quantity, boolean byRule) {
    }

    private static final Pattern ORDER_NO = Pattern.compile("[A-Z0-9]{10,}");
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}");

    private static final String PROMPT = """
            你是电商客服的意图分类器。对用户消息输出 JSON（不要输出其他内容）：
            {"intent":"PRESALE|ORDER|AFTERSALE|KNOWLEDGE|CHITCHAT|SPECS|ADDCART","keyword":"检索用关键词，售前必填","orderNo":"消息中的订单号，无则空串","productRef":"用户指代的具体商品（上文序号如'第一个'、商品名或'它'），无则空串","quantity":"购买数量，默认1"}
            分类标准：
            - 想买/推荐商品 = PRESALE
            - 问某个（上文提到的）商品的规格/颜色/型号/参数/库存/有没有货 = SPECS
            - 明确要买/加购/下单上文提到的商品 = ADDCART
            - 查订单/发货/物流/退款进度 = ORDER
            - 退货换货/售后申请 = AFTERSALE
            - 问政策/规则/常见问题 = KNOWLEDGE
            - 其余 = CHITCHAT""";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public Intent route(String message) {
        // 规则先行短路：显式订单号/强特征词不依赖模型
        Intent rule = ruleBased(message);
        if (rule != null) {
            return rule;
        }
        if (!llmClient.available()) {
            return new Intent(Type.PRESALE, extractKeyword(message), findOrderNo(message), "", 1, true);
        }
        try {
            String raw = llmClient.chat(PROMPT, message, 0.1, 300);
            Matcher m = JSON_BLOCK.matcher(raw);
            if (m.find()) {
                JsonNode node = objectMapper.readTree(m.group());
                Type type = switch (node.path("intent").asText("").toUpperCase()) {
                    case "ORDER" -> Type.ORDER;
                    case "AFTERSALE" -> Type.AFTERSALE;
                    case "KNOWLEDGE" -> Type.KNOWLEDGE;
                    case "CHITCHAT" -> Type.CHITCHAT;
                    case "SPECS" -> Type.SPECS;
                    case "ADDCART" -> Type.ADDCART;
                    default -> Type.PRESALE;
                };
                String keyword = node.path("keyword").asText("");
                String orderNo = node.path("orderNo").asText("");
                String productRef = node.path("productRef").asText("");
                int qty = Math.max(1, node.path("quantity").asInt(1));
                if (orderNo == null || orderNo.isBlank()) {
                    orderNo = findOrderNo(message);
                }
                if (type == Type.PRESALE && (keyword == null || keyword.isBlank())) {
                    keyword = extractKeyword(message);
                }
                return new Intent(type, keyword, orderNo, productRef, qty, false);
            }
        } catch (Exception e) {
            log.warn("[cs] 意图识别失败，回落规则: {}", e.getMessage());
        }
        return new Intent(Type.PRESALE, extractKeyword(message), findOrderNo(message), "", 1, true);
    }

    private Intent ruleBased(String message) {
        String no = findOrderNo(message);
        if (no != null && (message.contains("查") || message.contains("订单") || message.contains("物流") || message.contains("到哪"))) {
            return make(Type.ORDER, message, no, 1);
        }
        if (message.contains("订单") || message.contains("物流") || message.contains("发货") || message.contains("快递")) {
            return make(Type.ORDER, message, no, 1);
        }
        // SPECS / ADDCART 强特征词（它们可能不带商品名，依赖会话上下文）
        // 数量表达（来两台/再加一件/拿三个）也是加购强特征；句首数量短句单独匹配，避免误伤“来一台手机推荐”
        boolean addcart = message.matches(".*(加购|加入购物车|来一[单份个]|直接拍|就?要?(这个|那个|它|这款)[了嘛吧]|给我(来|拿|加)|下单买|再来|再加|加[一二两三四五六七八九十\\d]+[台个件份套部]).*")
                || (message.matches("^\\s*(来|要|加|拿)[一二两三四五六七八九十\\d]+[台个件份套部].*")
                    && !message.matches(".*(推荐|介绍|看看|哪款|什么|怎么选).*"));
        if (addcart) {
            return make(Type.ADDCART, message, no, extractQty(message));
        }
        if (message.matches(".*(规格|参数|配置|颜色|配色|尺寸|型号|版本|几个版本|有货|有没有货|库存|多大|多重).*")) {
            return make(Type.SPECS, message, no, 1);
        }
        if (message.contains("退货") || message.contains("换货") || message.contains("售后") || message.contains("退款")) {
            return make(Type.AFTERSALE, message, no, 1);
        }
        return null;
    }

    /** 消息中的购买数量：中文数字/阿拉伯数字，默认 1。 */
    static int extractQty(String message) {
        Matcher arabic = Pattern.compile("(\\d+)\\s*(个|台|件|部|双|只|盒)?").matcher(message);
        if (arabic.find()) {
            int n = Integer.parseInt(arabic.group(1));
            if (n >= 1 && n <= 99) {
                return n;
            }
        }
        String[] cn = {"一", "两", "二", "三", "四", "五"};
        int[] qty = {1, 2, 2, 3, 4, 5};
        for (int i = 0; i < cn.length; i++) {
            if (message.contains("来" + cn[i]) || message.contains("买" + cn[i]) || message.contains(cn[i] + "个")
                    || message.contains(cn[i] + "台") || message.contains(cn[i] + "件")) {
                return qty[i];
            }
        }
        return 1;
    }

    private static Intent make(Type t, String message, String orderNo, int qty) {
        return new Intent(t, extractKeyword(message), orderNo, "", qty, true);
    }

    private static String findOrderNo(String message) {
        Matcher m = ORDER_NO.matcher(message);
        return m.find() ? m.group() : null;
    }

    /** 轻量关键词抽取：去停用词后的前 12 字符。 */
    static String extractKeyword(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = message
                .replace("我想买", " ").replace("我想购买", " ").replace("帮我推荐", " ")
                .replace("请帮我", " ").replace("推荐一下", " ").replace("有什么", " ")
                .replace("多少钱", " ").replace("想买", " ").replace("推荐", " ")
                .replace("？", " ").replace("?", " ").replace("。", " ").replace("，", " ")
                .replace("吗", " ").replace("呢", " ").replace("吧", " ").trim();
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }
}
