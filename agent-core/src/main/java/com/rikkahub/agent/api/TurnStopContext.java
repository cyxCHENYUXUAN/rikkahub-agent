package com.rikkahub.agent.api;

import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.message.Message;

/** Immutable facts about the turn that is about to stop. */
public final class TurnStopContext {
    private final String sessionId;
    private final int turn;
    private final List<Message> messages;

    public TurnStopContext(String sessionId, int turn, List<Message> messages) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.turn = turn;
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public String sessionId() {
        return sessionId;
    }

    public int turn() {
        return turn;
    }

    public List<Message> messages() {
        return messages;
    }
}
