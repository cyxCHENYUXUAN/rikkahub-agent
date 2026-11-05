package com.rikkahub.agent.port;

import java.time.Instant;
import java.util.Objects;

import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.InputId;

/** A durable inbox entry: an input captured before the model executes it. */
public record PendingInput(InputId id, SessionId sessionId, InboxTarget target,
                           InboxItem item, Instant createdAt) {
    public PendingInput {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
