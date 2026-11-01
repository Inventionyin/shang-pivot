package com.shangpivot.commerce.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.commerce.entity.Attribute;
import com.shangpivot.commerce.mapper.AttributeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 属性查询：按类目树（含子孙）筛选。
 */
@Service
@RequiredArgsConstructor
public class AttributeService {
    private final AttributeMapper attributeMapper;
    private final BrandService brandService;

    public List<Attribute> listByCategory(Long categoryId) {
        if (categoryId == null) {
            return attributeMapper.selectList(new LambdaQueryWrapper<Attribute>()
                    .orderByAsc(Attribute::getSort)
                    .orderByAsc(Attribute::getId));
        }
        Set<Long> catIds = brandService.collectCategoryTreeIds(categoryId);
        return attributeMapper.selectList(new LambdaQueryWrapper<Attribute>()
                .in(Attribute::getCategoryId, catIds)
                .orderByAsc(Attribute::getSort)
                .orderByAsc(Attribute::getId));
    }
}
