package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "stock_log", schema = "commerce")
public class StockLog extends BaseEntity {
    private Long skuId;
    private Long warehouseId;
    private Integer changeQty;
    private String type;
    private String reason;
    private Long operatorId;
}
