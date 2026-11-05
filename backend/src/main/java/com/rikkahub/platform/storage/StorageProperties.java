package com.rikkahub.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rikkahub.storage")
public record StorageProperties(String dataDirectory, int busyTimeoutMs) {
    public StorageProperties {
        if (busyTimeoutMs < 1 || busyTimeoutMs > 60000) {
            throw new IllegalArgumentException("rikkahub.storage.busy-timeout-ms must be between 1 and 60000");
        }
    }
}
