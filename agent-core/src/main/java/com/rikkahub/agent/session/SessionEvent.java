package com.rikkahub.agent.session;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.session.event.SessionEventPayload;

/**
 * One append-only session log entry. {@code surface} is present only for events
 * that project into the model-visible message surface.
 */
public record SessionEvent(long seq, Instant time, SessionEventPayload data, SurfaceMeta surface,
                           boolean ignorable) {
    public SessionEvent {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1");
        }
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(data, "data");
    }

    public static SessionEvent of(long seq, Instant time, SessionEventPayload data) {
        return new SessionEvent(seq, time, data, null, false);
    }

    public static SessionEvent surface(long seq, Instant time, SessionEventPayload data,
                                       SurfaceOp op, List<Long> sourceSeqs) {
        return new SessionEvent(seq, time, data, new SurfaceMeta(op, sourceSeqs), false);
    }
}
