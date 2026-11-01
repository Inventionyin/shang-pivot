package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Map;

@Data
@TableName(value = "home_slot_item", schema = "commerce", autoResultMap = true)
public class HomeSlotItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long slotId;
    private Integer sort;
    private Long spuId;
    private String imageKey;
    private String linkType;
    private String linkValue;
    private String title;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extra;
    private String status;
}
