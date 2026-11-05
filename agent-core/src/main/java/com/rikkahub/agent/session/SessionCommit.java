package com.rikkahub.agent.session;

import java.util.List;
import java.util.Objects;

/** The only command that creates or changes a persisted session. */
public record SessionCommit(SessionId sessionId, long expectedSeq, SessionHeader header,
                            List<NewSessionEvent> events, List<SessionEvent> fixedEvents,
                            List<UsageRecord> usage, List<EffectIntent> effects,
                            InboxConsumption inboxConsumption, SessionStatusTransition status) {
    public SessionCommit {
        Objects.requireNonNull(sessionId, "sessionId");
        if (expectedSeq < 0) {
            throw new IllegalArgumentException("expectedSeq must be non-negative");
        }
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        fixedEvents = List.copyOf(Objects.requireNonNull(fixedEvents, "fixedEvents"));
        usage = List.copyOf(Objects.requireNonNull(usage, "usage"));
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
        if (!events.isEmpty() && !fixedEvents.isEmpty()) {
            throw new IllegalArgumentException("events and fixedEvents are mutually exclusive");
        }
        if (header != null) {
            if (expectedSeq != 0 || !sessionId.equals(header.id())) {
                throw new IllegalArgumentException("new session header must match sessionId and expectedSeq 0");
            }
            if (sessionId.equals(header.parentSessionId())) {
                throw new IllegalArgumentException("a session cannot be its own parent");
            }
            if (header.parentSessionId() != null && fixedEvents.isEmpty()) {
                throw new IllegalArgumentException("fork requires a nonempty closed-turn prefix");
            }
        }
        if (!fixedEvents.isEmpty()) {
            if (header == null || header.parentSessionId() == null || expectedSeq != 0) {
                throw new IllegalArgumentException("fixedEvents require a new fork header");
            }
            long expected = 1;
            for (SessionEvent event : fixedEvents) {
                if (event.seq() != expected++) {
                    throw new IllegalArgumentException("fixedEvents must start at 1 and be contiguous");
                }
            }
        }
    }
}
