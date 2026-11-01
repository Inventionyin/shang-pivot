package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "system_config", schema = "identity")
public class SystemConfig extends BaseEntity {
    private String configKey;
    private String configValue;
    private String category;
    private Boolean isSecret;
    private String remark;
}
