package com.shangpivot.customerservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 坐席：身份指向 identity 的管理端用户。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "seat", schema = "customerservice")
public class Seat extends BaseEntity {
    private Long adminUserId;
    private String nickname;
    private String status;
    private Integer maxConcurrent;

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_BUSY = "BUSY";
    public static final String STATUS_OFFLINE = "OFFLINE";
}
