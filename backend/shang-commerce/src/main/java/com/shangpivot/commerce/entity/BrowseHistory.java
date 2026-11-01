package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "browse_history", schema = "commerce")
public class BrowseHistory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long customerId;
    private Long spuId;
    private OffsetDateTime lastAt;
}
