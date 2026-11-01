package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.shangpivot.common.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "attribute", schema = "commerce", autoResultMap = true)
@Schema(description = "商品属性")
public class Attribute extends BaseEntity {
    @Schema(description = "所属类目ID", example = "1")
    private Long categoryId;
    @Schema(description = "属性名称", example = "颜色")
    private String name;
    @Schema(description = "属性类型：SPEC / PARAM", example = "SPEC")
    private String type;
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "可选值列表", example = "[\"红色\",\"蓝色\",\"黑色\"]")
    private List<String> options;
    @Schema(description = "排序值", example = "0")
    private Integer sort;
}
