package com.rikkahub.agent.port;

import java.time.Instant;
import java.util.Objects;

import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.session.ClaimToken;

/** One exclusive input lease; attempts includes the current claim. */
public record ClaimedInput(InputId id, SessionId sessionId, InboxTarget target, InboxItem item,
                           ClaimToken token, int attempts, Instant claimedAt, Instant leaseUntil) {
    public ClaimedInput {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (attempts < 1 || !leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("claim requires a positive attempt and lease");
        }
    }
}
