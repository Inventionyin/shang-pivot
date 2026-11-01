package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "category", schema = "commerce")
@Schema(description = "商品类目")
public class Category extends BaseEntity {
    @Schema(description = "父类目ID，顶级为 0", example = "0")
    private Long parentId;
    @Schema(description = "类目名称", example = "数码电器")
    private String name;
    @Schema(description = "层级", example = "1")
    private Integer level;
    @Schema(description = "排序值", example = "0")
    private Integer sort;
    @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
    private String status;
}
