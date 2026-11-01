package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "brand", schema = "commerce")
@Schema(description = "商品品牌")
public class Brand extends BaseEntity {
    @Schema(description = "所属类目 ID", example = "10")
    private Long categoryId;
    @Schema(description = "品牌名称", example = "华为")
    private String name;
    @Schema(description = "品牌 Logo URL", example = "https://cdn.example.com/brand/huawei.png")
    private String logo;
    @Schema(description = "排序值", example = "0")
    private Integer sort;
    @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
    private String status;
}
