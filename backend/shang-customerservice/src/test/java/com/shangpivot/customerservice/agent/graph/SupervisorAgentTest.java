package com.shangpivot.customerservice.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.customerservice.agent.IntentRouter;
import com.shangpivot.customerservice.agent.contract.AgentName;
import com.shangpivot.customerservice.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorAgentTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final SupervisorAgent supervisor = new SupervisorAgent(
            new IntentRouter(llmClient, new ObjectMapper()));

    SupervisorAgentTest() {
        when(llmClient.available()).thenReturn(false);
    }

    @Test
    void routesPresaleToShoppingGuideSearch() {
        SupervisorAgent.RouteResult route = supervisor.route("推荐一个手机，预算两千左右");

        assertEquals(AgentName.SHOPPING_GUIDE, route.agent());
        assertEquals(SupervisorAgent.Action.SEARCH_PRODUCTS, route.action());
        assertFalse(route.highRiskWrite());
    }

    @Test
    void routesSpecsToStockTool() {
        SupervisorAgent.RouteResult route = supervisor.route("它都有哪些规格，有货吗");

        assertEquals(AgentName.SHOPPING_GUIDE, route.agent());
        assertEquals(SupervisorAgent.Action.GET_STOCK, route.action());
    }

    @Test
    void routesAddToCartWithQuantity() {
        SupervisorAgent.RouteResult route = supervisor.route("来两台");

        assertEquals(AgentName.SHOPPING_GUIDE, route.agent());
        assertEquals(SupervisorAgent.Action.ADD_TO_CART, route.action());
        assertEquals(2, route.quantity());
    }

    @Test
    void routesOrderQuery() {
        SupervisorAgent.RouteResult route = supervisor.route("查一下订单 O20261107001");

        assertEquals(AgentName.ORDER, route.agent());
        assertEquals(SupervisorAgent.Action.GET_ORDER, route.action());
        assertEquals("O20261107001", route.orderNo());
    }

    @Test
    void routesCancelOrderAsHighRiskWrite() {
        SupervisorAgent.RouteResult route = supervisor.route("取消订单 O20261107001");

        assertEquals(AgentName.ORDER, route.agent());
        assertEquals(SupervisorAgent.Action.CANCEL_ORDER, route.action());
        assertTrue(route.highRiskWrite());
        assertFalse(route.needOrderNo());
    }

    @Test
    void asksForOrderNoWhenCancelWithoutIt() {
        SupervisorAgent.RouteResult route = supervisor.route("取消订单");

        assertEquals(SupervisorAgent.Action.CANCEL_ORDER, route.action());
        assertTrue(route.needOrderNo());
    }

    @Test
    void routesPolicyQuestionToKnowledgeSearchNotWrite() {
        SupervisorAgent.RouteResult route = supervisor.route("退货政策是什么");

        assertEquals(AgentName.AFTER_SALE, route.agent());
        assertEquals(SupervisorAgent.Action.SEARCH_KNOWLEDGE, route.action());
        assertFalse(route.highRiskWrite());
    }

    @Test
    void routesExplicitAfterSaleRequestToWrite() {
        SupervisorAgent.RouteResult route = supervisor.route("我要退货 O20261107001");

        assertEquals(AgentName.AFTER_SALE, route.agent());
        assertEquals(SupervisorAgent.Action.CREATE_AFTER_SALE, route.action());
        assertTrue(route.highRiskWrite());
    }

    @Test
    void routesConfirmReceiveAsHighRiskWrite() {
        SupervisorAgent.RouteResult route = supervisor.route("确认收货 O20261107001");

        assertEquals(AgentName.ORDER, route.agent());
        assertEquals(SupervisorAgent.Action.CONFIRM_RECEIVE, route.action());
        assertTrue(route.highRiskWrite());
    }
}
