package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 会话与消息的领域服务（状态机见 PRD §6.1）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final CsMessageMapper messageMapper;

    /** 取进行中会话，无则创建（开场即返回快捷问，前端拼装首屏）。 */
    public ChatSession getOrCreateActive(Long customerId, String clientType) {
        return getOrCreateActive(customerId, clientType, "", false, "", "");
    }

    /** 兼容测试/内部调用：未提供 clientKey 时沿用同账号复用语义。 */
    public ChatSession getOrCreateActive(Long customerId, String clientType,
                                         boolean isTest, String testSource, String testRunId) {
        return getOrCreateActive(customerId, clientType, "", isTest, testSource, testRunId);
    }

    /**
     * 测试流量（isTest=true）不参与真实会话复用：每次建独立会话，避免把测试消息写进真实用户会话。
     * clientKey 用于同一账号多浏览器/设备隔离；空值表示未提供（平台自动化测试、脚本客户端）。
     */
    public ChatSession getOrCreateActive(Long customerId, String clientType, String clientKey,
                                         boolean isTest, String testSource, String testRunId) {
        String key = normalizeClientKey(clientKey);
        ChatSession active = findActive(customerId, key, isTest, testSource);
        // 兼容上线前没有 client_key 的进行中会话：首个带 key 的客户端接管它，避免升级瞬间“会话丢失”。
        if (active == null && !key.isBlank()) {
            ChatSession legacy = findActive(customerId, "", isTest, testSource);
            if (legacy != null) {
                legacy.setClientKey(key);
                sessionMapper.updateById(legacy);
                active = legacy;
            }
        }
        if (active != null) {
            active.setResumed(true);
            return active;
        }
        ChatSession s = new ChatSession();
        s.setCustomerId(customerId);
        s.setClientType(clientType == null ? "h5" : clientType);
        s.setStatus(ChatSession.STATUS_AI_ONGOING);
        s.setStartedAt(OffsetDateTime.now());
        s.setLastMessageAt(s.getStartedAt());
        s.setIsTest(isTest);
        s.setTestSource(testSource == null ? "" : testSource);
        s.setTestRunId(testRunId == null ? "" : testRunId);
        s.setClientKey(key);
        s.setResumed(false);
        s.setPreviousSessionId(findPreviousSessionId(customerId, key, isTest, testSource));
        sessionMapper.insert(s);
        return s;
    }

    private ChatSession findActive(Long customerId, String clientKey, boolean isTest, String testSource) {
        LambdaQueryWrapper<ChatSession> query = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, customerId)
                .in(ChatSession::getStatus, ChatSession.STATUS_AI_ONGOING, ChatSession.STATUS_QUEUED,
                        ChatSession.STATUS_HUMAN_ONGOING);
        if (!isTest) {
            query.and(w -> w.eq(ChatSession::getIsTest, false).or().isNull(ChatSession::getIsTest));
        } else if ("human".equalsIgnoreCase(testSource)) {
            query.eq(ChatSession::getIsTest, true).eq(ChatSession::getTestSource, "human");
        } else {
            // 平台 AI 自动化测试不复用会话，避免跨用例污染。
            return null;
        }
        applyClientKey(query, clientKey);
        return sessionMapper.selectOne(query.orderByDesc(ChatSession::getId).last("LIMIT 1"));
    }

    private Long findPreviousSessionId(Long customerId, String clientKey, boolean isTest, String testSource) {
        if (isTest && !"human".equalsIgnoreCase(testSource)) {
            return null;
        }
        LambdaQueryWrapper<ChatSession> query = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, customerId)
                .eq(ChatSession::getStatus, ChatSession.STATUS_ENDED)
                .orderByDesc(ChatSession::getId)
                .last("LIMIT 1");
        if (!isTest) {
            query.and(w -> w.eq(ChatSession::getIsTest, false).or().isNull(ChatSession::getIsTest));
        } else {
            query.eq(ChatSession::getIsTest, true).eq(ChatSession::getTestSource, "human");
        }
        applyClientKey(query, clientKey);
        ChatSession previous = sessionMapper.selectOne(query);
        return previous == null ? null : previous.getId();
    }

    private static void applyClientKey(LambdaQueryWrapper<ChatSession> query, String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            query.and(w -> w.isNull(ChatSession::getClientKey).or().eq(ChatSession::getClientKey, ""));
        } else {
            query.eq(ChatSession::getClientKey, clientKey);
        }
    }

    private static String normalizeClientKey(String clientKey) {
        if (clientKey == null) {
            return "";
        }
        String key = clientKey.trim();
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    /** 结束当前所有进行中会话并新建一个（“开始新会话”；评测/冒烟用）。 */
    public ChatSession startFresh(Long customerId, String clientType) {
        return startFresh(customerId, clientType, "", false, "", "");
    }

    /** 兼容测试/内部调用：未提供 clientKey 时结束同账号所有进行中会话。 */
    public ChatSession startFresh(Long customerId, String clientType,
                                  boolean isTest, String testSource, String testRunId) {
        return startFresh(customerId, clientType, "", isTest, testSource, testRunId);
    }

    /** 测试流量只结束同类测试会话，不能误关真实用户正在进行的会话。 */
    public ChatSession startFresh(Long customerId, String clientType, String clientKey,
                                  boolean isTest, String testSource, String testRunId) {
        String key = normalizeClientKey(clientKey);
        LambdaQueryWrapper<ChatSession> query = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, customerId)
                .in(ChatSession::getStatus, ChatSession.STATUS_AI_ONGOING, ChatSession.STATUS_QUEUED,
                        ChatSession.STATUS_HUMAN_ONGOING);
        applyClientKey(query, key);
        if (isTest) {
            query.eq(ChatSession::getIsTest, true);
            // 人工体验与平台 AI 自动化测试互不干扰：只结束同一类测试会话。
            if (testSource != null && !testSource.isBlank()) {
                query.eq(ChatSession::getTestSource, testSource);
            }
        } else {
            query.and(w -> w.eq(ChatSession::getIsTest, false).or().isNull(ChatSession::getIsTest));
        }
        List<ChatSession> actives = sessionMapper.selectList(query);
        for (ChatSession s : actives) {
            s.setStatus(ChatSession.STATUS_ENDED);
            s.setEndedAt(OffsetDateTime.now());
            sessionMapper.updateById(s);
        }
        return getOrCreateActive(customerId, clientType, key, isTest, testSource, testRunId);
    }

    public ChatSession requireOwned(Long sessionId, Long customerId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || Boolean.TRUE.equals(s.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (!s.getCustomerId().equals(customerId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return s;
    }

    public PageResult<Map<String, Object>> listMine(Long customerId, int page, int size) {
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, customerId)
                .orderByDesc(ChatSession::getId));
        List<Map<String, Object>> records = p.getRecords().stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sessionId", s.getId());
            m.put("status", s.getStatus());
            m.put("summary", s.getSummary());
            m.put("startedAt", s.getStartedAt());
            m.put("endedAt", s.getEndedAt());
            m.put("lastMessageAt", s.getLastMessageAt());
            m.put("isTest", Boolean.TRUE.equals(s.getIsTest()));
            m.put("testSource", s.getTestSource());
            CsMessage last = messageMapper.selectOne(new LambdaQueryWrapper<CsMessage>()
                    .eq(CsMessage::getSessionId, s.getId())
                    .orderByDesc(CsMessage::getId)
                    .last("LIMIT 1"));
            m.put("lastMessage", last == null ? null : preview(last));
            return m;
        }).toList();
        return PageResult.of(p.getTotal(), records);
    }

    public List<Map<String, Object>> history(Long sessionId, Long beforeId, int limit) {
        LambdaQueryWrapper<CsMessage> q = new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, sessionId)
                .lt(beforeId != null && beforeId > 0, CsMessage::getId, beforeId)
                .orderByDesc(CsMessage::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100));
        return messageMapper.selectList(q).stream().map(this::toVo).toList();
    }

    public CsMessage saveUserMessage(Long sessionId, String content) {
        CsMessage msg = new CsMessage();
        msg.setSessionId(sessionId);
        msg.setRole(CsMessage.ROLE_USER);
        msg.setContentType(CsMessage.TYPE_TEXT);
        msg.setContent(content);
        messageMapper.insert(msg);
        touchSession(sessionId);
        return msg;
    }

    public CsMessage saveAssistantMessage(Long sessionId, String role, String content, String reasoning,
                                          Map<String, Object> metadata, Map<String, Object> toolTrace, boolean degraded) {
        CsMessage msg = new CsMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContentType(metadata != null && metadata.containsKey(CsMessage.META_ITEMS)
                ? CsMessage.TYPE_PRODUCT_CARDS : CsMessage.TYPE_TEXT);
        msg.setContent(content);
        msg.setReasoning(reasoning);
        msg.setMetadata(metadata);
        msg.setToolTrace(toolTrace);
        msg.setDegraded(degraded);
        messageMapper.insert(msg);
        touchSession(sessionId);
        return msg;
    }

    /** 消息落库后刷新会话活跃时间；空闲调度以此计算 30 分钟自动结束。 */
    private void touchSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        try {
            sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getId, sessionId)
                    .set(ChatSession::getLastMessageAt, now)
                    .set(ChatSession::getUpdatedAt, now));
        } catch (Exception e) {
            // 活跃时间是辅助数据：刷新失败不能影响用户消息/回答的落库与流式返回。
            log.warn("[cs] touch session {} failed: {}", sessionId, e.getMessage());
        }
    }

    /** 转人工 / 撤回。 */
    public ChatSession escalate(Long sessionId, Long customerId, boolean cancel) {
        ChatSession s = requireOwned(sessionId, customerId);
        if (cancel) {
            if (!ChatSession.STATUS_QUEUED.equals(s.getStatus())) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            s.setStatus(ChatSession.STATUS_AI_ONGOING);
        } else {
            if (!ChatSession.STATUS_AI_ONGOING.equals(s.getStatus())) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            s.setStatus(ChatSession.STATUS_QUEUED);
            s.setSeatId(null);
        }
        sessionMapper.updateById(s);
        return s;
    }

    public ChatSession endByCustomer(Long sessionId, Long customerId) {
        ChatSession s = requireOwned(sessionId, customerId);
        return end(s);
    }

    /** 首轮应答后自动生成会话摘要（取首条用户消息；不调用 LLM，零成本）。 */
    public void autoSummarize(Long sessionId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || (s.getSummary() != null && !s.getSummary().isBlank())) {
            return;
        }
        CsMessage first = messageMapper.selectOne(new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, sessionId)
                .eq(CsMessage::getRole, CsMessage.ROLE_USER)
                .orderByAsc(CsMessage::getId)
                .last("LIMIT 1"));
        if (first != null && first.getContent() != null && !first.getContent().isBlank()) {
            String text = first.getContent().trim();
            s.setSummary(text.length() > 40 ? text.substring(0, 40) + "…" : text);
            sessionMapper.updateById(s);
        }
    }

    public ChatSession end(ChatSession s) {
        if (ChatSession.STATUS_ENDED.equals(s.getStatus())) {
            return s;
        }
        s.setStatus(ChatSession.STATUS_ENDED);
        s.setEndedAt(OffsetDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    /**
     * 结束超过空闲阈值的进行中会话（AI/排队/人工均适用）。
     * 返回本次实际结束的会话数；调度器每分钟调用一次，多实例重复执行也安全。
     */
    public int endIdleSessions(OffsetDateTime now, Duration idleTimeout) {
        OffsetDateTime cutoff = now.minus(idleTimeout);
        return sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .in(ChatSession::getStatus, ChatSession.STATUS_AI_ONGOING, ChatSession.STATUS_QUEUED,
                        ChatSession.STATUS_HUMAN_ONGOING)
                // 以消息表真实时间为兜底：即使应用侧 last_message_at 刷新失败，也不会误结束正在聊的会话。
                .apply("COALESCE((SELECT MAX(m.created_at) FROM customerservice.message m "
                        + "WHERE m.session_id = chat_session.id AND m.deleted = FALSE), last_message_at) < {0}",
                        cutoff)
                .set(ChatSession::getStatus, ChatSession.STATUS_ENDED)
                .set(ChatSession::getEndedAt, now)
                .set(ChatSession::getUpdatedAt, now));
    }

    public Map<String, Object> toVo(CsMessage msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", msg.getId());
        m.put("sessionId", msg.getSessionId());
        m.put("role", msg.getRole());
        m.put("contentType", msg.getContentType());
        m.put("content", msg.getContent());
        // 历史回放不回传思考链，避免长期保存/暴露模型内部推理；实时流仍按原协议返回。
        m.put("reasoning", null);
        m.put("metadata", msg.getMetadata());
        m.put("degraded", Boolean.TRUE.equals(msg.getDegraded()));
        m.put("createdAt", msg.getCreatedAt());
        return m;
    }

    private static String preview(CsMessage msg) {
        if (msg.getContent() != null && msg.getContent().length() > 40) {
            return msg.getContent().substring(0, 40);
        }
        return msg.getContent();
    }
}
