package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 品牌/系列别名配置：用户叫法 → 商品名中的英文/系列 token。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "product_alias", schema = "customerservice")
public class ProductAlias extends BaseEntity {
    private String alias;
    private String targetToken;
    private Boolean enabled;
}
