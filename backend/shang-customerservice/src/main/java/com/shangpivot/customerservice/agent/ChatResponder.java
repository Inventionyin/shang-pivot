package com.shangpivot.customerservice.agent;

import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 一次 AI 应答的入口；legacy 与 graph 两种实现可切换。 */
public interface ChatResponder {

    void respond(SseEmitter emitter, ChatSession session, CsMessage userMsg);
}
