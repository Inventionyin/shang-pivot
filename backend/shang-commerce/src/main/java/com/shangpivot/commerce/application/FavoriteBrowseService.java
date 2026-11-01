package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.commerce.entity.BrowseHistory;
import com.shangpivot.commerce.entity.Favorite;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.BrowseHistoryMapper;
import com.shangpivot.commerce.mapper.FavoriteMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.api.CustomerQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** C 端收藏 / 足迹。 */
@Service
@RequiredArgsConstructor
public class FavoriteBrowseService {

    private final FavoriteMapper favoriteMapper;
    private final BrowseHistoryMapper browseHistoryMapper;
    private final SpuMapper spuMapper;
    private final CatalogReadService catalogReadService;
    private final CustomerQueryApi customerQueryApi;

    public PageResult<Map<String, Object>> favorites(int page, int size) {
        Long customerId = requireActive();
        Page<Favorite> p = favoriteMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getCustomerId, customerId)
                        .orderByDesc(Favorite::getId));
        Map<Long, Spu> spuMap = loadSpus(p.getRecords().stream().map(Favorite::getSpuId).filter(Objects::nonNull).toList());
        List<Map<String, Object>> records = p.getRecords().stream()
                .map(f -> spuMap.get(f.getSpuId()))
                .filter(Objects::nonNull)
                .map(catalogReadService::toCard)
                .toList();
        return PageResult.of(p.getTotal(), records);
    }

    @Transactional
    public void addFavorite(Long spuId) {
        Long customerId = requireActive();
        requireSpu(spuId);
        Long exists = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getCustomerId, customerId)
                .eq(Favorite::getSpuId, spuId));
        if (exists != null && exists > 0) {
            return;
        }
        Favorite f = new Favorite();
        f.setCustomerId(customerId);
        f.setSpuId(spuId);
        favoriteMapper.insert(f);
    }

    @Transactional
    public void removeFavorite(Long spuId) {
        Long customerId = requireActive();
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getCustomerId, customerId)
                .eq(Favorite::getSpuId, spuId));
    }

    public boolean isFavorite(Long spuId) {
        Long customerId = SecurityContexts.optionalUserId();
        if (customerId == null || spuId == null) {
            return false;
        }
        Long exists = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getCustomerId, customerId)
                .eq(Favorite::getSpuId, spuId));
        return exists != null && exists > 0;
    }

    public PageResult<Map<String, Object>> history(int page, int size) {
        Long customerId = requireActive();
        Page<BrowseHistory> p = browseHistoryMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<BrowseHistory>()
                        .eq(BrowseHistory::getCustomerId, customerId)
                        .orderByDesc(BrowseHistory::getLastAt));
        Map<Long, Spu> spuMap = loadSpus(p.getRecords().stream().map(BrowseHistory::getSpuId).filter(Objects::nonNull).toList());
        List<Map<String, Object>> records = p.getRecords().stream().map(h -> {
            Spu spu = spuMap.get(h.getSpuId());
            if (spu == null) {
                return null;
            }
            Map<String, Object> card = catalogReadService.toCard(spu);
            card.put("lastAt", h.getLastAt());
            return card;
        }).filter(Objects::nonNull).toList();
        return PageResult.of(p.getTotal(), records);
    }

    @Transactional
    public void touchHistory(Long customerId, Long spuId) {
        if (customerId == null || spuId == null) {
            return;
        }
        BrowseHistory existing = browseHistoryMapper.selectOne(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getCustomerId, customerId)
                .eq(BrowseHistory::getSpuId, spuId));
        if (existing != null) {
            existing.setLastAt(OffsetDateTime.now());
            browseHistoryMapper.updateById(existing);
            return;
        }
        BrowseHistory h = new BrowseHistory();
        h.setCustomerId(customerId);
        h.setSpuId(spuId);
        h.setLastAt(OffsetDateTime.now());
        browseHistoryMapper.insert(h);
    }

    @Transactional
    public void clearHistory() {
        Long customerId = requireActive();
        browseHistoryMapper.delete(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getCustomerId, customerId));
    }

    private Map<Long, Spu> loadSpus(List<Long> spuIds) {
        if (spuIds.isEmpty()) {
            return Map.of();
        }
        return spuMapper.selectBatchIds(spuIds).stream()
                .collect(Collectors.toMap(Spu::getId, s -> s, (a, b) -> a));
    }

    private void requireSpu(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
    }

    private Long requireActive() {
        Long id = SecurityContexts.requireUserId();
        if (!customerQueryApi.isActive(id)) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        return id;
    }
}
