package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "operation_log", schema = "identity")
public class OperationLog extends BaseEntity {
    private Long userId;
    private String username;
    private String module;
    private String action;
    private String params;
    private String ip;
    private String ua;
    private String result;
}
