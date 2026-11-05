package com.rikkahub.agent.session.event;

import java.util.Objects;

public record TurnEnd(int turn, TurnEndReason reason) implements SessionEventPayload {
    public TurnEnd {
        Objects.requireNonNull(reason, "reason");
    }
}
