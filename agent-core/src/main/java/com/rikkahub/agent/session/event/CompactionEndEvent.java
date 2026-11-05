package com.rikkahub.agent.session.event;

import java.util.Objects;

/** End of a compaction attempt; {@code error} is null on success. */
public record CompactionEndEvent(String compactionId, String error) implements SessionEventPayload {
    public CompactionEndEvent {
        Objects.requireNonNull(compactionId, "compactionId");
    }

    /** Backward-compatible constructor for older call sites and stored events. */
    public CompactionEndEvent(String compactionId) {
        this(compactionId, null);
    }
}
