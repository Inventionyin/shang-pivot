package com.shangpivot.customerservice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.customerservice.llm.LlmClient;
import com.shangpivot.customerservice.retrieval.ProductConstraintParser;
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

    public record Intent(Type type, String keyword, String orderNo, String productRef, int quantity, boolean byRule)
            implements java.io.Serializable {
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
        // 明确购买“上文第 N 款/这个/那个/它”：优先于售前检索。
        // 注意不要用裸“买/要”匹配，“想买手机推荐”仍应是售前。
        String refWord = "(第\\s*[一二三四五1-5]\\s*(?:款|个)|上面(?:那个|这款|的商品)?|刚才(?:那个|的)?|"
                + "刚刚(?:那个|的)?|前面(?:那个|的)?|上一个|这个|那个|它|这款|同款)";
        boolean purchaseRef = message.matches(".*(购买|买|要|拿|来|下单|拍下).{0,8}" + refWord + ".*")
                || message.matches(".*(就买|就要|直接买|帮我买|给我买|买下|拍下|下单).*")
                || message.matches(".*(?:把|将|给|帮我).{0,8}" + refWord + ".*(?:加|放|加入|加进|添加).*");
        // “加进/加入购物车”“把某商品加进购物车”必须走加购，而不是重新检索。
        boolean cartPhrase = message.matches(".*(?:加入|加进|加到|放进|放入|添加).{0,4}购物车.*")
                || message.matches(".*(?:把|将|给|帮我).{0,20}(?:加入|加进|加到|放进|放入|添加|加).{0,4}购物车.*");
        // 单句购买确认（“我现在买”“买了”“下单吧”）：依赖上文商品，不能再做商品检索。
        boolean purchaseConfirm = message.matches(
                "^\\s*(?:我)?(?:现在|现|就|直接)?(?:我)?(?:买|要|下单|拍下)(?:了|吧|呀|啊|啦|这个|这款|它)?\\s*[。！!]?\\s*$");
        boolean addcart = cartPhrase || purchaseConfirm || purchaseRef
                || message.matches(".*(加购|加入购物车|来一[单份个]|直接拍|就?要?(这个|那个|它|这款)[了嘛吧]|给我(来|拿|加)|下单买|再来|再加|加[一二两三四五六七八九十\\d]+[台个件份套部]).*")
                || (message.matches("^\\s*(来|要|加|拿)[一二两三四五六七八九十\\d]+[台个件份套部].*")
                    && !message.matches(".*(推荐|介绍|看看|哪款|什么|怎么选).*"));
        if (addcart) {
            String productRef = (cartPhrase || purchaseRef) && !purchaseConfirm
                    ? extractProductRef(message) : "";
            return new Intent(Type.ADDCART, extractKeyword(message), no, productRef,
                    extractQty(message), true);
        }
        // 对比/二选一必须走商品检索拿两张卡，不能被“吸力/降噪”等属性词误判成单商品 SPECS
        if (message.matches(".*(对比|比较|哪个.{0,6}(好|强|便宜|贵|划算|值得|适合)|vs|VS).*")) {
            return make(Type.PRESALE, message, no, 1);
        }
        if (message.matches(".*(规格|参数|配置|颜色|配色|尺寸|型号|版本|几个版本|有货|有没有货|库存|多大|多重).*")) {
            return make(Type.SPECS, message, no, 1);
        }
        // 优惠/折扣是售前咨询，不能被通用 LLM 分类误判成售后或知识问答。
        if (message.matches(".*(优惠|折扣|活动价|便宜点|便宜些|有优惠).*")
                && !message.matches(".*(退货|换货|售后|退款|退换|订单|物流|发货|快递).*")) {
            return make(Type.PRESALE, message, no, 1);
        }
        if (message.contains("退货") || message.contains("换货") || message.contains("售后") || message.contains("退款")
                || message.contains("退换") || message.contains("无理由") || message.contains("可以退")
                || message.matches(".*(能退|退吗|质量问题|运费谁|退运费|怎么退).*")) {
            return make(Type.AFTERSALE, message, no, 1);
        }
        // “在吗/想问下 + 品类 + 购买词”是导购，不是闲聊
        if (ProductConstraintParser.parse(message).hasCategories()
                && message.matches(".*(买|想买|想给|推荐|预算|多少钱|有货|看看|换新).*")) {
            return make(Type.PRESALE, message, no, 1);
        }
        if (message.matches(".*(你是谁|你叫什么|天气|几点|讲个笑话|谢谢|你好|在吗).*")) {
            return make(Type.CHITCHAT, message, no, 1);
        }
        return null;
    }

    /** 消息中的购买数量：中文数字/阿拉伯数字，默认 1；“第二个”是商品序号，不是数量 2。 */
    static int extractQty(String message) {
        String cleaned = message == null ? ""
                : message.replaceAll("第\\s*[一二三四五1-5]\\s*[个款台]?", " ");
        // 数字必须跟在购买动词后（“来2台”），否则商品名里的数字（Mate X5 / iPhone 15）会被误当数量
        Matcher arabic = Pattern.compile("(?:来|要|加|拿|买|拍)\\s*(\\d+)\\s*(个|台|件|部|双|只|盒)?").matcher(cleaned);
        if (arabic.find()) {
            int n = Integer.parseInt(arabic.group(1));
            if (n >= 1 && n <= 99) {
                return n;
            }
        }
        String[] cn = {"一", "两", "二", "三", "四", "五"};
        int[] qty = {1, 2, 2, 3, 4, 5};
        for (int i = 0; i < cn.length; i++) {
            if (cleaned.contains("来" + cn[i]) || cleaned.contains("买" + cn[i]) || cleaned.contains(cn[i] + "个")
                    || cleaned.contains(cn[i] + "台") || cleaned.contains(cn[i] + "件")) {
                return qty[i];
            }
        }
        return 1;
    }

    /**
     * 从“帮我把上面那个美的风冷冰箱加进购物车”这类句子里提取商品指代。
     * 返回空串表示只说了“上面那个/这个/它”，交由编排器按上文卡片解析。
     */
    static String extractProductRef(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if (message.matches(".*第\\s*[一二三四五1-5].*")) {
            return "";
        }
        String ref = message
                .replaceAll("^(?:(?:帮我|请|麻烦|我想|我要|想要|把|将|给|上面那个|上面这款|上面|"
                        + "刚才那个|刚才|刚刚|前面那个|前面|上一个|这个|那个|这款|那款|同款|它|的)\\s*)+", " ")
                .replaceAll("(?:加入|加进|加到|放进|放入|添加|加).{0,4}购物车", " ")
                .replaceAll("(?:购买|买|下单|拍下)(?:了|吧|呀|啊|啦)?\\s*$", " ")
                .replaceAll("[\\s，。！？、：；\"'“”‘’（）【】《》,.!?]", "")
                .trim();
        if (ref.isBlank() || ref.length() > 40 || ref.matches(".*(那个|这个|它|上面|刚才|同款).*")) {
            return "";
        }
        return ref;
    }

    /** 商品名匹配归一化：去掉空格/中英文标点并转小写（“美的 风冷 冰箱”≈“美的风冷冰箱”）。 */
    public static String normalizeProductText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static Intent make(Type t, String message, String orderNo, int qty) {
        return new Intent(t, extractKeyword(message), orderNo, "", qty, true);
    }

    private static String findOrderNo(String message) {
        Matcher m = ORDER_NO.matcher(message);
        return m.find() ? m.group() : null;
    }

    private static final java.util.List<String> CATEGORY_WORDS = java.util.List.of(
            "手机", "耳机", "吸尘器", "扫地机器人", "帐篷", "洗衣机", "跑步鞋", "蓝牙耳机", "自行车");

    /** 轻量关键词抽取：先识别品类词（长描述也能命中），再去停用词取前 12 字符。 */
    public static String extractKeyword(String message) {
        if (message == null) {
            return "";
        }
        // “手机号/手机号码”是 PII 语境，不是手机品类；避免优惠咨询被当成导购检索
        String normalized = message.replace("手机号码", " ").replace("手机号", " ");
        java.util.List<String> categories = CATEGORY_WORDS.stream()
                .filter(normalized::contains)
                .toList();
        if (!categories.isEmpty()) {
            return String.join(" ", categories);
        }
        String cleaned = normalized
                // 加购数量前缀（“来三个收纳盒”“来一台 HUAWEI Mate X5”）不是商品名的一部分
                .replaceFirst("^\\s*(来|要|加|拿)\\s*[一二两三四五六七八九十\\d]+\\s*[台个件份套双顶盒瓶支]?", " ")
                // 加购句式（“帮我把上面那个美的风冷冰箱加进购物车”）保留商品名，去掉指代/动作
                .replaceAll("(?:帮我|请|麻烦|把|将|给|上面那个|上面|刚才那个|刚才|前面那个|前面|上一个|"
                        + "这个|那个|这款|那款|同款|它)", " ")
                .replaceAll("(?:加入|加进|加到|放进|放入|添加|加).{0,4}购物车", " ")
                .replace("购物车", " ")
                .replace("我想买", " ").replace("我想购买", " ").replace("帮我推荐", " ")
                .replace("请帮我", " ").replace("推荐一下", " ").replace("有什么", " ")
                .replace("多少钱", " ").replace("想买", " ").replace("推荐", " ")
                .replace("？", " ").replace("?", " ").replace("。", " ").replace("，", " ")
                .replace("吗", " ").replace("呢", " ").replace("吧", " ")
                .replaceFirst("^个", " ").trim();
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }
}
