package com.shangpivot.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shangpivot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "file_object", schema = "identity")
public class FileObject extends BaseEntity {
    private String bucket;
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    private String bizType;
    private Long uploaderId;
    private String visibility;
}
