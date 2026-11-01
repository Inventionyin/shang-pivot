package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "in_out_order", schema = "commerce")
public class InOutOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String billNo;
    private String type;
    private Long warehouseId;
    private Long operatorId;
    private String remark;
    private String relatedOrderNo;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private String traceId;
}
