package com.rikkahub.agent.api;

import java.util.concurrent.CompletableFuture;

import com.rikkahub.agent.session.SessionId;

/**
 * A live agent. Input methods only enqueue; they never write session events
 * directly.
 */
public interface Agent {
    AgentId id();

    SessionId sessionId();

    AgentStatus status();

    AgentContext context();

    void send(InboxItem item, InboxTarget target);

    /** Enqueues a new turn. */
    void followup(InboxItem item);

    /** Applies the input at the nearest step boundary. */
    void steer(InboxItem item);

    /** Adds context for the next step without waking the loop. */
    void inject(InboxItem item);

    CancelResult cancel(CancelOptions options);

    CompletableFuture<Void> whenIdle();

    void runMaintenance();
}
