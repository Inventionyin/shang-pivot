package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "in_out_item", schema = "commerce")
public class InOutItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long billId;
    private Long skuId;
    private Integer qty;
    private String remark;
}
