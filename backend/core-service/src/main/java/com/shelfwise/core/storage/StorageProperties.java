package com.shelfwise.core.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible storage settings. Defaults match the local SeaweedFS in docker-compose; deployed
 * environments (Cloudflare R2) set STORAGE_* variables. Leave {@code endpoint} empty for AWS itself.
 */
@ConfigurationProperties("shelfwise.storage")
public record StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey,
                                boolean pathStyle) {
}
