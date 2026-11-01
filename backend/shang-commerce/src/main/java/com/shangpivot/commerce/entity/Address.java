package com.shangpivot.commerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "address", schema = "commerce")
public class Address extends BaseEntity {
    private Long customerId;
    private String name;
    private String phone;
    private String province;
    private String city;
    private String district;
    private String detail;
    private Boolean isDefault;
}
