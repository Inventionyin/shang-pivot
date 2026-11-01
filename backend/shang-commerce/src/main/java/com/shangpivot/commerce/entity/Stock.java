package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "stock", schema = "commerce")
public class Stock extends BaseEntity {
    private Long skuId;
    private Long warehouseId;
    private Integer physicalStock;
    private Integer lockedStock;
    private Integer lowThreshold;
    @Version
    private Integer version;
}
