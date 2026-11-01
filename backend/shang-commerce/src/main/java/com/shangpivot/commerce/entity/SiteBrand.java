package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "site_brand", schema = "commerce")
public class SiteBrand {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String logoKey;
    private String name;
    private String slogan;
    private String faviconKey;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
    private Long updatedBy;
    private String traceId;
}
