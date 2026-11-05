package com.rikkahub.plugins.workspaceruntimes.sandbox;

/**
 * Configuration options for command and process sandbox isolation.
 */
public record SandboxConfig(
        Driver driver,
        String containerImage,
        String memoryLimit,
        String cpuLimit,
        boolean networkEnabled
) {
    public enum Driver {
        AUTO,
        LOCAL,
        DOCKER,
        PODMAN
    }

    public static SandboxConfig defaults() {
        return new SandboxConfig(
                Driver.AUTO,
                "alpine:latest",
                "512m",
                "1.0",
                false // Network is isolated by default for security
        );
    }
}
