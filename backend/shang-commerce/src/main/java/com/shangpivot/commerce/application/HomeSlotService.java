package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.HomeSlot;
import com.shangpivot.commerce.entity.HomeSlotItem;
import com.shangpivot.commerce.entity.Sku;
import com.shangpivot.commerce.entity.Spu;
import com.shangpivot.commerce.mapper.HomeSlotItemMapper;
import com.shangpivot.commerce.mapper.HomeSlotMapper;
import com.shangpivot.commerce.mapper.SkuMapper;
import com.shangpivot.commerce.mapper.SpuMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HomeSlotService {

    private static final int MAX_ITEMS = 10;

    private final HomeSlotMapper homeSlotMapper;
    private final HomeSlotItemMapper homeSlotItemMapper;
    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;

    public List<Map<String, Object>> listAdmin() {
        List<HomeSlot> slots = homeSlotMapper.selectList(new LambdaQueryWrapper<HomeSlot>().orderByAsc(HomeSlot::getSort));
        return slots.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("code", s.getCode());
            m.put("title", s.getTitle());
            m.put("status", s.getStatus());
            m.put("sort", s.getSort());
            m.put("validFrom", s.getValidFrom());
            m.put("validTo", s.getValidTo());
            long itemCount = homeSlotItemMapper.selectCount(new LambdaQueryWrapper<HomeSlotItem>().eq(HomeSlotItem::getSlotId, s.getId()));
            m.put("itemCount", itemCount);
            return m;
        }).toList();
    }

    public Map<String, Object> getByCode(String code) {
        HomeSlot slot = requireByCode(code);
        List<HomeSlotItem> items = homeSlotItemMapper.selectList(new LambdaQueryWrapper<HomeSlotItem>()
                .eq(HomeSlotItem::getSlotId, slot.getId())
                .orderByAsc(HomeSlotItem::getSort));
        Set<Long> spuIds = items.stream().map(HomeSlotItem::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Spu> spuMap = spuIds.isEmpty() ? Map.of() :
                spuMapper.selectBatchIds(spuIds).stream().collect(Collectors.toMap(Spu::getId, s -> s, (a, b) -> a));
        Map<Long, java.math.BigDecimal> minPrice = minPriceBySpu(spuIds);
        List<Map<String, Object>> itemVos = new ArrayList<>();
        for (HomeSlotItem item : items) {
            Map<String, Object> iv = new HashMap<>();
            iv.put("id", item.getId());
            iv.put("sort", item.getSort());
            iv.put("spuId", item.getSpuId());
            iv.put("imageKey", item.getImageKey());
            iv.put("linkType", item.getLinkType());
            iv.put("linkValue", item.getLinkValue());
            iv.put("title", item.getTitle());
            iv.put("extra", item.getExtra());
            iv.put("status", item.getStatus());
            if (item.getSpuId() != null) {
                Spu spu = spuMap.get(item.getSpuId());
                if (spu != null) {
                    iv.put("spuName", spu.getName());
                    iv.put("spuStatus", spu.getStatus());
                    iv.put("pic", spu.getPics() == null || spu.getPics().isEmpty() ? null : spu.getPics().get(0));
                    iv.put("soldCount", spu.getSoldCount() == null ? 0 : spu.getSoldCount());
                    iv.put("price", minPrice.get(item.getSpuId()));
                }
            }
            itemVos.add(iv);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", slot.getId());
        m.put("code", slot.getCode());
        m.put("title", slot.getTitle());
        m.put("status", slot.getStatus());
        m.put("sort", slot.getSort());
        m.put("validFrom", slot.getValidFrom());
        m.put("validTo", slot.getValidTo());
        m.put("items", itemVos);
        return m;
    }

    @Transactional
    public Map<String, Object> saveByCode(String code, SlotSaveReq req) {
        HomeSlot slot = requireByCode(code);
        if (StringUtils.hasText(req.getTitle())) {
            slot.setTitle(req.getTitle());
        }
        if (StringUtils.hasText(req.getStatus())) {
            slot.setStatus(req.getStatus());
        }
        if (req.getSort() != null) {
            slot.setSort(req.getSort());
        }
        slot.setValidFrom(req.getValidFrom());
        slot.setValidTo(req.getValidTo());
        slot.setUpdatedAt(OffsetDateTime.now());
        homeSlotMapper.updateById(slot);

        if (req.getItems() != null) {
            if (req.getItems().size() > MAX_ITEMS) {
                throw new BizException(ErrorCode.BAD_REQUEST, "每个槽位最多 " + MAX_ITEMS + " 条");
            }
            homeSlotItemMapper.delete(new LambdaQueryWrapper<HomeSlotItem>().eq(HomeSlotItem::getSlotId, slot.getId()));
            int i = 0;
            for (SlotItemReq itemReq : req.getItems()) {
                HomeSlotItem item = new HomeSlotItem();
                item.setSlotId(slot.getId());
                item.setSort(itemReq.getSort() != null ? itemReq.getSort() : i++);
                item.setSpuId(itemReq.getSpuId());
                item.setImageKey(itemReq.getImageKey());
                item.setLinkType(StringUtils.hasText(itemReq.getLinkType()) ? itemReq.getLinkType() : "NONE");
                item.setLinkValue(itemReq.getLinkValue());
                item.setTitle(itemReq.getTitle());
                item.setExtra(itemReq.getExtra());
                item.setStatus(StringUtils.hasText(itemReq.getStatus()) ? itemReq.getStatus() : "ON");
                homeSlotItemMapper.insert(item);
            }
        }
        return getByCode(code);
    }

    /** C 端首页：仅 ON 且在时间窗内；SPU 须上架。 */
    public List<Map<String, Object>> listForStorefront() {
        OffsetDateTime now = OffsetDateTime.now();
        List<HomeSlot> slots = homeSlotMapper.selectList(new LambdaQueryWrapper<HomeSlot>()
                .eq(HomeSlot::getStatus, "ON")
                .orderByAsc(HomeSlot::getSort));
        List<Map<String, Object>> result = new ArrayList<>();
        for (HomeSlot slot : slots) {
            if (slot.getValidFrom() != null && now.isBefore(slot.getValidFrom())) {
                continue;
            }
            if (slot.getValidTo() != null && now.isAfter(slot.getValidTo())) {
                continue;
            }
            List<HomeSlotItem> items = homeSlotItemMapper.selectList(new LambdaQueryWrapper<HomeSlotItem>()
                    .eq(HomeSlotItem::getSlotId, slot.getId())
                    .eq(HomeSlotItem::getStatus, "ON")
                    .orderByAsc(HomeSlotItem::getSort));
            Set<Long> spuIds = items.stream().map(HomeSlotItem::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, Spu> spuMap = spuIds.isEmpty() ? Map.of() :
                    spuMapper.selectBatchIds(spuIds).stream()
                            .filter(s -> "ON".equals(s.getStatus()))
                            .collect(Collectors.toMap(Spu::getId, s -> s));
            Map<Long, java.math.BigDecimal> minPrice = minPriceBySpu(spuIds);
            List<Map<String, Object>> itemVos = new ArrayList<>();
            for (HomeSlotItem item : items) {
                if (item.getSpuId() != null && !spuMap.containsKey(item.getSpuId())) {
                    continue;
                }
                Map<String, Object> iv = new HashMap<>();
                iv.put("imageKey", item.getImageKey());
                iv.put("imageUrl", item.getImageKey());
                iv.put("linkType", item.getLinkType());
                iv.put("linkValue", item.getLinkValue());
                iv.put("title", item.getTitle());
                if (item.getSpuId() != null) {
                    Spu spu = spuMap.get(item.getSpuId());
                    iv.put("spuId", spu.getId());
                    iv.put("name", spu.getName());
                    iv.put("pic", spu.getPics() == null || spu.getPics().isEmpty() ? null : spu.getPics().get(0));
                    iv.put("soldCount", spu.getSoldCount() == null ? 0 : spu.getSoldCount());
                    iv.put("price", minPrice.get(item.getSpuId()));
                }
                itemVos.add(iv);
            }
            Map<String, Object> sv = new HashMap<>();
            sv.put("code", slot.getCode());
            sv.put("title", slot.getTitle());
            sv.put("items", itemVos);
            result.add(sv);
        }
        return result;
    }

    private HomeSlot requireByCode(String code) {
        HomeSlot slot = homeSlotMapper.selectOne(new LambdaQueryWrapper<HomeSlot>().eq(HomeSlot::getCode, code));
        if (slot == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运营位不存在");
        }
        return slot;
    }

    private Map<Long, java.math.BigDecimal> minPriceBySpu(Set<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>().in(Sku::getSpuId, spuIds));
        Map<Long, java.math.BigDecimal> out = new HashMap<>();
        for (Sku sku : skus) {
            if (sku.getSpuId() == null || sku.getPrice() == null) {
                continue;
            }
            out.merge(sku.getSpuId(), sku.getPrice(), java.math.BigDecimal::min);
        }
        return out;
    }

    @Data
    public static class SlotSaveReq {
        private String title;
        private String status;
        private Integer sort;
        private OffsetDateTime validFrom;
        private OffsetDateTime validTo;
        private List<SlotItemReq> items;
    }

    @Data
    public static class SlotItemReq {
        private Integer sort;
        private Long spuId;
        private String imageKey;
        private String linkType;
        private String linkValue;
        private String title;
        private Map<String, Object> extra;
        private String status;
    }
}
