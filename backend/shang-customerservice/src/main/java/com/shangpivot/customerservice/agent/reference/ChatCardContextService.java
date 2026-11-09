package com.shangpivot.customerservice.agent.reference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.commerce.api.ProductCardDTO;
import com.shangpivot.commerce.api.ProductQueryApi;
import com.shangpivot.customerservice.entity.CsMessage;
import com.shangpivot.customerservice.mapper.CsMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话内商品卡上下文：最近 N 条 PRODUCT_CARDS 消息合并去重。
 *
 * <p>只取最近 3 条、最多 10 个 SPU：既支持“刚才那个冰箱”跨一次推荐切换，
 * 又不会把很早的商品误当成当前指代。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCardContextService {

    private static final int DEFAULT_BATCHES = 3;
    private static final int MAX_CARDS = 10;

    private final CsMessageMapper messageMapper;
    private final ProductQueryApi productQueryApi;
    private final ObjectMapper objectMapper;

    public List<ProductCardDTO> latestCards(Long sessionId) {
        return recentCards(sessionId, 1);
    }

    public List<ProductCardDTO> recentCards(Long sessionId) {
        return recentCards(sessionId, DEFAULT_BATCHES);
    }

    public List<ProductCardDTO> recentCards(Long sessionId, int batches) {
        if (sessionId == null || batches <= 0) {
            return List.of();
        }
        try {
            List<CsMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                    .eq(CsMessage::getSessionId, sessionId)
                    .eq(CsMessage::getRole, CsMessage.ROLE_ASSISTANT)
                    .eq(CsMessage::getContentType, CsMessage.TYPE_PRODUCT_CARDS)
                    .orderByDesc(CsMessage::getId)
                    .last("LIMIT " + Math.min(batches, 5)));
            if (messages.isEmpty()) {
                return List.of();
            }
            Set<Long> spuIds = new LinkedHashSet<>();
            for (CsMessage message : messages) {
                if (message.getMetadata() == null || message.getMetadata().get(CsMessage.META_ITEMS) == null) {
                    continue;
                }
                List<Map<String, Object>> items = objectMapper.convertValue(
                        message.getMetadata().get(CsMessage.META_ITEMS),
                        new TypeReference<List<Map<String, Object>>>() { });
                for (Map<String, Object> item : items) {
                    Object raw = item.get("spuId");
                    if (raw == null) {
                        continue;
                    }
                    try {
                        spuIds.add(Long.valueOf(String.valueOf(raw)));
                    } catch (NumberFormatException ignored) {
                        // 忽略脏卡片，不影响整轮解析
                    }
                    if (spuIds.size() >= MAX_CARDS) {
                        break;
                    }
                }
                if (spuIds.size() >= MAX_CARDS) {
                    break;
                }
            }
            if (spuIds.isEmpty()) {
                return List.of();
            }
            Map<Long, ProductCardDTO> byId = productQueryApi.getBySpuIds(spuIds).stream()
                    .collect(java.util.stream.Collectors.toMap(ProductCardDTO::spuId, c -> c, (a, b) -> a));
            List<ProductCardDTO> result = new ArrayList<>();
            for (Long spuId : spuIds) {
                ProductCardDTO card = byId.get(spuId);
                if (card != null) {
                    result.add(card);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("[cs] read recent cards failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 当前会话是否有可指代的商品。 */
    public boolean hasContext(Long sessionId) {
        return !recentCards(sessionId, 1).isEmpty();
    }

    /** 测试/日志用：判断卡片是否属于同一 SPU。 */
    public static boolean sameProduct(ProductCardDTO a, ProductCardDTO b) {
        return a != null && b != null && Objects.equals(a.spuId(), b.spuId());
    }
}
