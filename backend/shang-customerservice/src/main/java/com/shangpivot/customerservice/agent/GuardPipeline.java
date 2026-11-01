package com.shangpivot.customerservice.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 安全护栏（PRD §8.13）：输入侧注入检测 + 输出侧 PII 脱敏。
 * 规则保持简单可解释，命中即记录审计（由调用方落 audit_log）。
 */
@Component
public class GuardPipeline {

    /** 典型提示注入特征（试图改写/泄露系统指令）。 */
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(忽略(之前|以上|上面|先前)(的)?(所有)?(指令|提示|设定)" +
                    "|ignore (all )?(previous|prior|above) (instructions?|prompts?)" +
                    "|(你的|系统)?(系统)?提示词" +
                    "|system prompt" +
                    "|你是一?个?(全新|不同)的" +
                    "|developer mode|越狱|jailbreak)");

    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9][0-9]{9})(?![0-9])");

    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9Xx])([1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:[0-2]\\d|3[01])\\d{3}[0-9Xx])(?![0-9Xx])");

    /** 用户消息入库前的 PII 掩码（手机号/身份证号；邮箱保留用于身份识别）。 */
    public String maskPii(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String out = PHONE.matcher(input).replaceAll(m -> m.group().substring(0, 3) + "****" + m.group().substring(7));
        out = ID_CARD.matcher(out).replaceAll(m -> m.group().substring(0, 4) + "**********" + m.group().substring(14));
        return out;
    }

    /**
     * 思考残留剥离（兜底）：极少数网关把规划语混入正文开头。
     * 若正文前缀为一段不含 CJK 的文本且后续存在 CJK，则丢弃该前缀（正常中文回答不受影响）。
     */
    public String stripPlanningPrefix(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        int firstCjk = -1;
        for (int i = 0; i < content.length(); i++) {
            if (Character.UnicodeScript.of(content.codePointAt(i)) == Character.UnicodeScript.HAN) {
                firstCjk = i;
                break;
            }
        }
        if (firstCjk > 0 && firstCjk <= 160) {
            return content.substring(firstCjk).stripLeading();
        }
        return content;
    }

    public record GuardResult(boolean injectionSuspected, String sanitized) {
    }

    /** 输入检查：返回是否疑似注入（原文照常参与对话，仅触发护栏与审计）。 */
    public GuardResult inspectInput(String input) {
        if (input == null) {
            return new GuardResult(false, "");
        }
        return new GuardResult(INJECTION.matcher(input).find(), input);
    }

    /** 输出过滤：手机号脱敏 + 基本清理。 */
    public String filterOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        return PHONE.matcher(output).replaceAll(m -> m.group().substring(0, 3) + "****" + m.group().substring(7));
    }
}
