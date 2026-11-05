package com.rikkahub.plugins.workspaceruntimes.sandbox;

/**
 * Read-only capabilities descriptor describing active process sandbox and PTY terminal support.
 */
public record SandboxCapabilities(
        String activeDriver,
        boolean containerAvailable,
        String containerEngine,
        String containerVersion,
        String containerImage,
        boolean networkIsolated,
        boolean ptySupported,
        String ptyMode
) {
    public static SandboxCapabilities localDefault(boolean ptySupported, String ptyMode) {
        return new SandboxCapabilities(
                "LOCAL",
                false,
                "none",
                "unavailable",
                "none",
                false,
                ptySupported,
                ptyMode
        );
    }
}
