package com.shangpivot.common.oss;

import java.io.InputStream;
import java.util.Map;

public interface ObjectStorage {
    PresignResult presignPut(String objectKey, String contentType, long expireSeconds);

    void putObject(String objectKey, byte[] data, String contentType);

    InputStream getObject(String objectKey);

    ObjectMeta statObject(String objectKey);

    String publicUrl(String objectKey);

    record PresignResult(String uploadUrl, String objectKey, String publicUrl, Map<String, String> headers) {}

    record ObjectMeta(String contentType, long size) {}
}
