package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "warehouse", schema = "commerce")
@Schema(description = "仓库")
public class Warehouse extends BaseEntity {
    @Schema(description = "仓库名称", example = "华东仓")
    private String name;
    @Schema(description = "状态：ENABLED / DISABLED", example = "ENABLED")
    private String status;
}
