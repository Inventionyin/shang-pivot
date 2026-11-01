package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "favorite", schema = "commerce")
public class Favorite {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long customerId;
    private Long spuId;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
