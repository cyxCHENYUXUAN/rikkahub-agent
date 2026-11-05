package com.rikkahub.agent.execution;

import java.util.Objects;

/** A bounded UTF-8 preview and optional opaque, host-owned spill reference. */
public record CapturedOutput(String text, long totalBytes, boolean truncated,
                             String spillReference, boolean spillTruncated) {
    public CapturedOutput {
        Objects.requireNonNull(text, "text");
        if (totalBytes < 0) throw new IllegalArgumentException("negative byte count");
    }
    public static CapturedOutput empty() {
        return new CapturedOutput("", 0, false, null, false);
    }
}
