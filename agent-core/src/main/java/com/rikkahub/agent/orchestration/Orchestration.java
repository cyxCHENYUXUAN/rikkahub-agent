package com.rikkahub.agent.orchestration;

import java.time.Instant;
import java.util.*;
import com.rikkahub.agent.session.EffectIntent;

/** Host-owned orchestration records. External text never carries authority. */
public final class Orchestration {
    private Orchestration() {}
    public enum Decision { OBSERVE, ACCEPT, STOP, IGNORE, UNAVAILABLE }
    public enum TicketStatus { OPEN, WAITING, READY, ENDED }
    public enum AssignmentStatus { PENDING, RUNNING, ENDED }
    public enum Report { PROGRESS, WAITING, READY, BLOCKED, FAILED, DROPPED }
    public record Trigger(String id, String source, String externalId, String threadId, String text, Instant createdAt) {}
    public record Thread(String id, String source, String key, Instant createdAt) {}
    public record Eval(String id, String triggerId, String threadId, Decision recommendation,
                       Decision decision, String reason, Instant createdAt) {}
    public record Mandate(String id, long version, String assistantId, String configJson, Instant expiresAt,
                          boolean revoked, Instant createdAt) {
        public boolean valid(Instant now) { return !revoked && now.isBefore(expiresAt); }
    }
    public record Ticket(String id, String evalId, String threadId, String mandateId, TicketStatus status,
                         String title, String note, Instant createdAt, Instant updatedAt) {}
    public record Assignment(String id, String ticketId, String mandateId, String sessionId,
                             AssignmentStatus status, Report report, String input, String note,
                             boolean human, Instant createdAt, Instant updatedAt, String requestId) {}
    /** Local notification only; delivering it never sends email, chat or webhook messages. */
    public record Reply(String id, String ticketId, String text, Instant createdAt) {}
    public record Snapshot(Map<String,Trigger> triggers, Map<String,Thread> threads, Map<String,Eval> evals,
                           Map<String,Mandate> mandates, Map<String,Ticket> tickets,
                           Map<String,Assignment> assignments, Map<String,Reply> replies) {
        public Snapshot {
            triggers=ordered(triggers); threads=ordered(threads); evals=ordered(evals);
            mandates=ordered(mandates); tickets=ordered(tickets); assignments=ordered(assignments); replies=ordered(replies);
        }
        private static <T> Map<String,T> ordered(Map<String,T> values) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
    /** Mutable only inside EngineStore.transact; snapshots contain immutable records. */
    public static final class State {
        final Map<String,Trigger> triggers=new LinkedHashMap<>();
        final Map<String,Thread> threads=new LinkedHashMap<>();
        final Map<String,Eval> evals=new LinkedHashMap<>();
        final Map<String,Mandate> mandates=new LinkedHashMap<>();
        final Map<String,Ticket> tickets=new LinkedHashMap<>();
        final Map<String,Assignment> assignments=new LinkedHashMap<>();
        final Map<String,Reply> replies=new LinkedHashMap<>();
        private final List<EffectIntent> effects=new ArrayList<>();
        public State() {}
        public State(Snapshot snapshot) {
            triggers.putAll(snapshot.triggers()); threads.putAll(snapshot.threads()); evals.putAll(snapshot.evals());
            mandates.putAll(snapshot.mandates()); tickets.putAll(snapshot.tickets());
            assignments.putAll(snapshot.assignments()); replies.putAll(snapshot.replies());
        }
        public Snapshot snapshot() { return new Snapshot(triggers,threads,evals,mandates,tickets,assignments,replies); }
        public List<EffectIntent> effects() { return List.copyOf(effects); }
        void effect(EffectIntent effect) { effects.add(effect); }
    }
}
