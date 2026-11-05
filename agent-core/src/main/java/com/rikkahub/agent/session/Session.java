package com.rikkahub.agent.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.message.MessageRole;
import com.rikkahub.agent.message.MessageSource;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.message.ToolResultBlock;
import com.rikkahub.agent.session.event.AssistantMessageEvent;
import com.rikkahub.agent.session.event.SessionEventPayload;
import com.rikkahub.agent.session.event.SystemMessageEvent;
import com.rikkahub.agent.session.event.ToolResultEvent;
import com.rikkahub.agent.session.event.TurnEnd;
import com.rikkahub.agent.session.event.TurnStart;
import com.rikkahub.agent.session.event.UserMessageEvent;

/**
 * Immutable, append-only session log. The message view consumed by the model is
 * derived from surface events on demand and is never stored in this object.
 */
public final class Session {
    private final SessionHeader header;
    private final List<SessionEvent> events;

    public Session(SessionHeader header, List<SessionEvent> events) {
        this.header = Objects.requireNonNull(header, "header");
        List<SessionEvent> copy = List.copyOf(Objects.requireNonNull(events, "events"));
        validateOrder(copy);
        for (SessionEvent event : copy) {
            validateSurfacePayload(event);
        }
        this.events = copy;
    }

    public SessionHeader header() {
        return header;
    }

    public List<SessionEvent> events() {
        return events;
    }

    public int formatVersion() {
        return header.formatVersion();
    }

    /** Returns 0 when the session has no events. */
    public long lastSeq() {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).seq();
    }

    public long nextSeq() {
        return lastSeq() + 1;
    }

    public Session append(SessionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.seq() != nextSeq()) {
            throw new IllegalArgumentException(
                    "event seq " + event.seq() + " does not follow " + lastSeq());
        }
        validateSurfacePayload(event);
        List<SessionEvent> next = new ArrayList<>(events.size() + 1);
        next.addAll(events);
        next.add(event);
        return new Session(header, next);
    }

    public Session appendAll(List<SessionEvent> additions) {
        Objects.requireNonNull(additions, "events");
        List<SessionEvent> incoming = List.copyOf(additions);
        long expected = nextSeq();
        List<SessionEvent> next = new ArrayList<>(events.size() + incoming.size());
        next.addAll(events);
        for (SessionEvent event : incoming) {
            if (event.seq() != expected) {
                throw new IllegalArgumentException(
                        "event seq " + event.seq() + " does not follow " + (expected - 1));
            }
            validateSurfacePayload(event);
            next.add(event);
            expected++;
        }
        return new Session(header, next);
    }

    /**
     * Projects surface events into the model-visible message list. Replace events
     * drop the messages sourced from the shadowed seq range and insert the
     * replacement at the first removed position.
     */
    public List<Message> deriveMessages() {
        List<SurfaceEntry> surface = new ArrayList<>();
        for (SessionEvent event : events) {
            if (event.surface() == null || event.ignorable()) {
                continue;
            }
            SurfaceOp op = event.surface().op();
            Message message = toMessage(event);
            if (op instanceof SurfaceOp.SurfaceAppend) {
                surface.add(new SurfaceEntry(event.seq(), message));
            } else if (op instanceof SurfaceOp.SurfaceReplace replace) {
                int insertion = -1;
                List<SurfaceEntry> kept = new ArrayList<>(surface.size() + 1);
                for (SurfaceEntry entry : surface) {
                    if (entry.sourceSeq() >= replace.startSeq()
                            && entry.sourceSeq() <= replace.endSeq()) {
                        if (insertion < 0) {
                            insertion = kept.size();
                        }
                    } else {
                        kept.add(entry);
                    }
                }
                SurfaceEntry replacement = new SurfaceEntry(event.seq(), message);
                if (insertion < 0) {
                    kept.add(replacement);
                } else {
                    kept.add(insertion, replacement);
                }
                surface = kept;
            } else {
                throw new IllegalArgumentException("unsupported surface op: " + op);
            }
        }
        List<Message> messages = new ArrayList<>(surface.size());
        for (SurfaceEntry entry : surface) {
            messages.add(entry.message());
        }
        return List.copyOf(messages);
    }

    public Optional<SessionEvent> lastEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    /** True when the most recent turn boundary is a turn start. */
    public boolean hasOpenTurn() {
        for (int i = events.size() - 1; i >= 0; i--) {
            SessionEventPayload data = events.get(i).data();
            if (data instanceof TurnStart) {
                return true;
            }
            if (data instanceof TurnEnd) {
                return false;
            }
        }
        return false;
    }

    private static Message toMessage(SessionEvent event) {
        SessionEventPayload data = event.data();
        String id = "m-" + event.seq();
        if (data instanceof SystemMessageEvent system) {
            return new Message(id, MessageRole.SYSTEM, List.of(new TextBlock(system.text())),
                    event.time(), MessageSource.SYSTEM);
        }
        if (data instanceof UserMessageEvent user) {
            return new Message(id, MessageRole.USER, user.content(), event.time(),
                    MessageSource.USER);
        }
        if (data instanceof AssistantMessageEvent assistant) {
            return new Message(id, MessageRole.ASSISTANT, assistant.content(), event.time(),
                    MessageSource.MODEL);
        }
        if (data instanceof ToolResultEvent result) {
            return new Message(id, MessageRole.TOOL,
                    List.<ContentBlock>of(new ToolResultBlock(result.callId(), result.toolName(),
                            result.content(), result.isError())),
                    event.time(), MessageSource.TOOL);
        }
        throw new IllegalArgumentException(
                "payload cannot be projected to a message: " + data.getClass().getName());
    }

    private static void validateSurfacePayload(SessionEvent event) {
        if (event.surface() == null) {
            return;
        }
        SessionEventPayload data = event.data();
        if (!(data instanceof SystemMessageEvent)
                && !(data instanceof UserMessageEvent)
                && !(data instanceof AssistantMessageEvent)
                && !(data instanceof ToolResultEvent)) {
            throw new IllegalArgumentException(
                    "payload cannot be projected to a surface message: "
                            + data.getClass().getName());
        }
    }

    private static void validateOrder(List<SessionEvent> list) {
        long expected = 1;
        for (SessionEvent event : list) {
            if (event.seq() != expected) {
                throw new IllegalArgumentException(
                        "events must start at seq 1 and be contiguous: expected " + expected
                                + " but was " + event.seq());
            }
            expected++;
        }
    }

    private record SurfaceEntry(long sourceSeq, Message message) {
    }
}
