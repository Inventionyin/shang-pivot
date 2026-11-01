package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "home_slot", schema = "commerce")
public class HomeSlot {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String code;
    private String title;
    private String status;
    private Integer sort;
    private OffsetDateTime validFrom;
    private OffsetDateTime validTo;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
