package com.shangpivot.commerce.application;

import com.shangpivot.commerce.entity.SiteBrand;
import com.shangpivot.commerce.mapper.SiteBrandMapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.SecurityContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SiteBrandService {

    private static final long BRAND_ID = 1L;

    private final SiteBrandMapper siteBrandMapper;

    public Map<String, Object> get() {
        SiteBrand brand = ensure();
        return toVo(brand);
    }

    @Transactional
    public Map<String, Object> update(Map<String, String> body) {
        SiteBrand brand = ensure();
        if (body.containsKey("logoKey")) {
            brand.setLogoKey(body.get("logoKey"));
        }
        if (body.containsKey("name")) {
            String name = body.get("name");
            if (!StringUtils.hasText(name) || name.length() > 30) {
                throw new BizException(ErrorCode.BAD_REQUEST, "站点名须为 1～30 字符");
            }
            brand.setName(name.trim());
        }
        if (body.containsKey("slogan")) {
            String slogan = body.get("slogan");
            if (slogan != null && slogan.length() > 50) {
                throw new BizException(ErrorCode.BAD_REQUEST, "Slogan 不超过 50 字符");
            }
            brand.setSlogan(slogan);
        }
        if (body.containsKey("faviconKey")) {
            brand.setFaviconKey(body.get("faviconKey"));
        }
        brand.setUpdatedAt(OffsetDateTime.now());
        try {
            brand.setUpdatedBy(SecurityContexts.requireUserId());
        } catch (BizException ignored) {
            // C 端只读不写
        }
        siteBrandMapper.updateById(brand);
        return toVo(brand);
    }

    private SiteBrand ensure() {
        SiteBrand brand = siteBrandMapper.selectById(BRAND_ID);
        if (brand == null) {
            brand = new SiteBrand();
            brand.setId(BRAND_ID);
            brand.setName("尚品汇");
            brand.setSlogan("选好物，享生活");
            brand.setUpdatedAt(OffsetDateTime.now());
            siteBrandMapper.insert(brand);
        }
        return brand;
    }

    private Map<String, Object> toVo(SiteBrand brand) {
        Map<String, Object> m = new HashMap<>();
        m.put("logoKey", brand.getLogoKey());
        m.put("logoUrl", brand.getLogoKey());
        m.put("name", brand.getName());
        m.put("slogan", brand.getSlogan());
        m.put("faviconKey", brand.getFaviconKey());
        m.put("faviconUrl", brand.getFaviconKey());
        return m;
    }
}
