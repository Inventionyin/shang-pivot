package com.shangpivot.common.oss;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioObjectStorage implements ObjectStorage {

    private final OssProperties properties;
    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(properties.normalizedEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Override
    public PresignResult presignPut(String objectKey, String contentType, long expireSeconds) {
        try {
            Map<String, String> headers = new HashMap<>();
            if (contentType != null) {
                headers.put("Content-Type", contentType);
            }
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .expiry((int) expireSeconds, TimeUnit.SECONDS)
                    .extraHeaders(headers)
                    .build());
            return new PresignResult(url, objectKey, publicUrl(objectKey), headers);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to presign upload: " + e.getMessage(), e);
        }
    }

    @Override
    public void putObject(String objectKey, byte[] data, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload object: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get object: " + e.getMessage(), e);
        }
    }

    @Override
    public ObjectMeta statObject(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .build());
            return new ObjectMeta(stat.contentType(), stat.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stat object: " + e.getMessage(), e);
        }
    }

    /**
     * 返回后端代理路径。MinIO 桶通常私有，浏览器无法直链访问；
     * 经 {@code GET /admin/files/view} 鉴权代理后可在 &lt;img&gt; 中展示。
     * 若配置了 {@code public-base-url}（如 CDN），则优先使用。
     */
    @Override
    public String publicUrl(String objectKey) {
        String base = properties.getPublicBaseUrl();
        if (StringUtils.hasText(base)) {
            if (base.endsWith("/")) {
                return base + objectKey;
            }
            return base + "/" + objectKey;
        }
        return "/admin/files/view?objectKey=" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    }
}
