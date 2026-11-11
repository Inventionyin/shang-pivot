package com.shangpivot.customerservice.agent.graph;

import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.agent.contract.AgentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Supervisor 的规则路由层：先用确定性规则选子 Agent 与动作，避免每轮多一次 LLM 调用。
 * 规则未覆盖的意图仍由 IntentRouter 的 LLM 分类兜底。
 */
@Component
@RequiredArgsConstructor
public class SupervisorAgent {

    public enum Action {
        NONE,
        SEARCH_PRODUCTS,
        GET_STOCK,
        ADD_TO_CART,
        GET_ORDER,
        CANCEL_ORDER,
        CONFIRM_RECEIVE,
        CREATE_AFTER_SALE,
        SEARCH_KNOWLEDGE
    }

    public record RouteResult(
            IntentRouter.Intent intent,
            AgentName agent,
            Action action,
            boolean highRiskWrite,
            boolean needOrderNo,
            String orderNo,
            String productRef,
            int quantity) implements java.io.Serializable {
    }

    private static final Pattern CANCEL = Pattern.compile(".*(取消订单|把订单取消|不要了|不想要了|退掉).*");
    private static final Pattern CONFIRM = Pattern.compile(".*(确认收货|已经收到|收到货了|货已收到).*");
    private static final Pattern AFTER_SALE_WRITE = Pattern.compile(
            ".*(申请售后|我要退货|我要退款|帮我退货|帮我退款|申请退款|申请退货|我要换货|申请换货).*");
    private static final Pattern POLICY_QUESTION = Pattern.compile(".*(政策|规则|流程|怎么申请|如何申请|多久|几天|能不能退|可以退吗).*");

    private final IntentRouter intentRouter;

    public RouteResult route(String userText) {
        return route(userText, null);
    }

    /** fixedIntent 非空时用于承接上一轮澄清（裸序号/确认），不再调用 LLM 重新分类。 */
    public RouteResult route(String userText, IntentRouter.Intent fixedIntent) {
        IntentRouter.Intent intent = fixedIntent != null ? fixedIntent : intentRouter.route(userText);
        String text = userText == null ? "" : userText;
        String orderNo = intent.orderNo();

        // 高风险写意图优先于普通问答；缺少订单号时先追问，不生成待确认动作。
        if (CANCEL.matcher(text).matches()) {
            return new RouteResult(intent, AgentName.ORDER, Action.CANCEL_ORDER, true,
                    orderNo == null || orderNo.isBlank(), orderNo, intent.productRef(), 1);
        }
        if (CONFIRM.matcher(text).matches()) {
            return new RouteResult(intent, AgentName.ORDER, Action.CONFIRM_RECEIVE, true,
                    orderNo == null || orderNo.isBlank(), orderNo, intent.productRef(), 1);
        }
        if (AFTER_SALE_WRITE.matcher(text).matches() && !POLICY_QUESTION.matcher(text).matches()) {
            return new RouteResult(intent, AgentName.AFTER_SALE, Action.CREATE_AFTER_SALE, true,
                    orderNo == null || orderNo.isBlank(), orderNo, intent.productRef(), 1);
        }

        return switch (intent.type()) {
            case PRESALE -> new RouteResult(intent, AgentName.SHOPPING_GUIDE, Action.SEARCH_PRODUCTS,
                    false, false, orderNo, intent.productRef(), 1);
            case SPECS -> new RouteResult(intent, AgentName.SHOPPING_GUIDE, Action.GET_STOCK,
                    false, false, orderNo, intent.productRef(), 1);
            case ADDCART -> new RouteResult(intent, AgentName.SHOPPING_GUIDE, Action.ADD_TO_CART,
                    false, false, orderNo, intent.productRef(), Math.max(1, intent.quantity()));
            case ORDER -> new RouteResult(intent, AgentName.ORDER, Action.GET_ORDER,
                    false, false, orderNo, intent.productRef(), 1);
            case AFTERSALE -> new RouteResult(intent, AgentName.AFTER_SALE, Action.SEARCH_KNOWLEDGE,
                    false, false, orderNo, intent.productRef(), 1);
            case KNOWLEDGE -> new RouteResult(intent, AgentName.KNOWLEDGE,
                    Action.SEARCH_KNOWLEDGE, false, false, orderNo, intent.productRef(), 1);
            case CHITCHAT -> new RouteResult(intent, AgentName.KNOWLEDGE,
                    Action.NONE, false, false, orderNo, intent.productRef(), 1);
        };
    }

    /** 工具名到子 Agent 的映射（确认动作时恢复上下文用）。 */
    public static AgentName agentForTool(String toolName) {
        return switch (toolName) {
            case "cancelOrder", "confirmReceive", "getOrder", "getLogistics" -> AgentName.ORDER;
            case "createAfterSale", "createTicket" -> AgentName.AFTER_SALE;
            case "searchProducts", "getProduct", "getStock", "addToCart" -> AgentName.SHOPPING_GUIDE;
            default -> AgentName.KNOWLEDGE;
        };
    }
}
