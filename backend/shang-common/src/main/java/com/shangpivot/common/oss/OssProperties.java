package com.shangpivot.common.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "shang.oss")
public class OssProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String publicBaseUrl;
    private long presignExpireSeconds = 600;
    private long maxSizeBytes = 10485760;

    public String normalizedEndpoint() {
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:9000";
        }
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        return "http://" + endpoint;
    }
}
