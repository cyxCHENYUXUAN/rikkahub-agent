package com.rikkahub.agent.loop;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.session.ClaimToken;
import com.rikkahub.agent.port.ClaimedInput;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.port.InboxStore;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.port.PendingInput;
import com.rikkahub.agent.port.SessionStore;
import com.rikkahub.agent.session.UsageRecord;
import com.rikkahub.agent.session.EffectIntent;
import com.rikkahub.agent.session.InboxConsumption;
import com.rikkahub.agent.session.NewSessionEvent;
import com.rikkahub.agent.session.Session;
import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionCommitResult;
import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SessionHeader;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.SessionSnapshot;
import com.rikkahub.agent.session.SessionStatus;
import com.rikkahub.agent.session.SessionSummary;
import com.rikkahub.agent.session.SurfaceOp;
import com.rikkahub.agent.session.event.TurnEnd;
import com.rikkahub.agent.session.event.TurnStart;
import com.rikkahub.agent.session.event.UserMessageEvent;

/**
 * Thread-safe, process-local session and inbox backend for embedded runtimes.
 * Use the same instance for both ports: a single lock makes commit, claim and
 * consumption atomic. Data is retained for this instance's lifetime only.
 * Storage and clock/identifier dependencies perform no callbacks after commit.
 */
public final class InMemoryAgentStore implements SessionStore, InboxStore {
    private final Object lock = new Object();
    private final Clock clock;
    private final IdGenerator ids;
    private final Map<SessionId, StoredSession> sessions = new LinkedHashMap<>();
    private final Map<InputId, StoredInput> inputs = new LinkedHashMap<>();
    private final Map<ClaimToken, StoredInput> claims = new HashMap<>();
    private final Set<ClaimToken> issuedTokens = new HashSet<>();
    private final Set<String> effectIds = new HashSet<>();
    private InboxConsumption completing;
    private Instant completingAt;

    public InMemoryAgentStore(Clock clock, IdGenerator ids) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public SessionSnapshot load(SessionId id) {
        synchronized (lock) {
            return requireSession(id).snapshot();
        }
    }

    /**
     * Returns a prefix from sequence 1 through min(lastSeq, afterSeq + limit).
     * SessionSnapshot wraps Session, whose invariant forbids starting in the
     * middle of a log; earlier events therefore remain in the returned prefix.
     */
    @Override
    public SessionSnapshot loadRange(SessionId id, long afterSeq, int limit) {
        if (afterSeq < 0 || limit < 1) {
            throw new IllegalArgumentException("afterSeq must be non-negative and limit positive");
        }
        synchronized (lock) {
            SessionSnapshot snapshot = requireSession(id).snapshot();
            Session session = snapshot.session();
            long requestedEnd = afterSeq > Long.MAX_VALUE - limit ? Long.MAX_VALUE : afterSeq + limit;
            int end = (int) Math.min(session.lastSeq(), requestedEnd);
            return new SessionSnapshot(new Session(session.header(), session.events().subList(0, end)),
                    snapshot.status(), snapshot.updatedAt());
        }
    }

    @Override
    public SessionCommitResult commit(SessionCommit command) {
        Objects.requireNonNull(command, "commit");
        synchronized (lock) {
            StoredSession previous = sessions.get(command.sessionId());
            boolean creating = previous == null;
            if (creating && command.header() == null) {
                throw new NoSuchElementException("new session requires a header: " + command.sessionId());
            }
            if (!creating && command.header() != null) {
                throw new IllegalStateException("session already exists: " + command.sessionId());
            }
            long lastSeq = creating ? 0 : previous.snapshot().session().lastSeq();
            if (command.expectedSeq() != lastSeq) {
                throw new ConcurrentModificationException("session sequence conflict: expected "
                        + command.expectedSeq() + " but was " + lastSeq);
            }
            SessionStatus current = creating ? SessionStatus.CREATED : previous.snapshot().status();
            SessionStatus next = current;
            if (command.status() != null) {
                if (command.status().expected() != current) {
                    throw new ConcurrentModificationException("session status conflict: expected "
                            + command.status().expected() + " but was " + current);
                }
                next = command.status().next();
            }
            if (!command.fixedEvents().isEmpty()) {
                validateFork(command);
            }
            for (UsageRecord usage : command.usage()) {
                if (!command.sessionId().equals(usage.sessionId())) {
                    throw new IllegalArgumentException("usage belongs to another session");
                }
            }
            Set<String> newEffectIds = new HashSet<>();
            for (EffectIntent effect : command.effects()) {
                if (effectIds.contains(effect.id()) || !newEffectIds.add(effect.id())) {
                    throw new IllegalArgumentException("duplicate effect id: " + effect.id());
                }
            }

            Instant now = now();
            List<SessionEvent> events = new ArrayList<>();
            if (!creating) {
                events.addAll(previous.snapshot().session().events());
            }
            events.addAll(command.fixedEvents());
            long sequence = lastSeq;
            for (NewSessionEvent event : command.events()) {
                sequence = Math.incrementExact(sequence);
                events.add(new SessionEvent(sequence, now, event.data(), event.surface(), event.ignorable()));
            }
            SessionHeader header = creating ? command.header() : previous.snapshot().session().header();
            Session session = new Session(header, events);
            var usage = new ArrayList<UsageRecord>();
            var effects = new ArrayList<EffectIntent>();
            if (!creating) {
                usage.addAll(previous.usage());
                effects.addAll(previous.effects());
            }
            usage.addAll(command.usage());
            effects.addAll(command.effects());
            StoredSession staged = new StoredSession(new SessionSnapshot(session, next, now),
                    List.copyOf(usage), List.copyOf(effects));
            if (command.inboxConsumption() != null) {
                StoredInput input = requireClaim(command.inboxConsumption().claimToken(),
                        command.inboxConsumption().inputId(), now);
                if (!input.pending.sessionId().equals(command.sessionId())) {
                    throw new IllegalArgumentException("input belongs to another session");
                }
                validateConsumption(command, input, creating ? null : previous.snapshot().session(), next);
            }

            // Nothing visible changes before all batch validation has succeeded.
            // The completion hook is usable only during this lock-held transaction.
            if (command.inboxConsumption() != null) {
                completing = command.inboxConsumption();
                completingAt = now;
                try {
                    complete(completing.claimToken(), completing.inputId());
                } finally {
                    completing = null;
                    completingAt = null;
                }
            }
            sessions.put(command.sessionId(), staged);
            effectIds.addAll(newEffectIds);
            // Metadata version is independent of log progress; this command has
            // no product metadata mutation, so the in-memory value remains 0.
            return new SessionCommitResult(command.sessionId(), session.lastSeq(), session.lastSeq(), 0);
        }
    }

    @Override
    public List<SessionSummary> list() {
        synchronized (lock) {
            return sessions.entrySet().stream().map(entry -> {
                SessionSnapshot snapshot = entry.getValue().snapshot();
                return new SessionSummary(entry.getKey(), snapshot.status(), snapshot.session().lastSeq(),
                        snapshot.updatedAt());
            }).toList();
        }
    }

    /** Immutable records committed for this session, in commit order. */
    public List<UsageRecord> listUsage(SessionId id) {
        synchronized (lock) {
            return requireSession(id).usage();
        }
    }

    /** Immutable stored effect intents; callers dispatch only after commit returns. */
    public List<EffectIntent> listEffects(SessionId id) {
        synchronized (lock) {
            return requireSession(id).effects();
        }
    }

    @Override
    public void enqueue(PendingInput input) {
        Objects.requireNonNull(input, "input");
        synchronized (lock) {
            requireSession(input.sessionId());
            StoredInput previous = inputs.get(input.id());
            if (previous != null) {
                if (!previous.pending.equals(input)) {
                    throw new IllegalStateException("input id already exists: " + input.id());
                }
                return;
            }
            inputs.put(input.id(), new StoredInput(input));
        }
    }

    @Override
    public ClaimedInput claim(SessionId id, InboxTarget target, String owner, Duration lease) {
        Objects.requireNonNull(id, "id");
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        synchronized (lock) {
            requireSession(id);
            Instant now = now();
            Instant until = now.plus(lease);
            reclaimExpired(now);
            for (StoredInput input : inputs.values()) {
                if (input.status != InputStatus.PENDING || !input.pending.sessionId().equals(id)
                        || (target != null && input.pending.target() != target)) {
                    continue;
                }
                ClaimToken token = new ClaimToken(ids.newId("claim"));
                if (issuedTokens.contains(token)) {
                    throw new IllegalStateException("identifier generator reused a claim token");
                }
                int attempts = Math.incrementExact(input.attempts);
                ClaimedInput result = new ClaimedInput(input.pending.id(), id, input.pending.target(),
                        input.pending.item(), token, attempts, now, until);
                issuedTokens.add(token);
                claims.put(token, input);
                input.status = InputStatus.IN_PROGRESS;
                input.token = token;
                input.attempts = attempts;
                input.leaseUntil = until;
                return result;
            }
            return null;
        }
    }

    /** Outside commit this hook always fails, preventing consumption without events. */
    @Override
    public void complete(ClaimToken token, InputId inputId) {
        synchronized (lock) {
            if (completing == null || !completing.claimToken().equals(token)
                    || !completing.inputId().equals(inputId)) {
                throw new IllegalStateException("complete requires a SessionCommit inbox consumption");
            }
            StoredInput input = requireClaim(token, inputId, completingAt);
            resetClaim(input, InputStatus.CONSUMED);
        }
    }

    @Override
    public void release(ClaimToken token, boolean retryable) {
        Objects.requireNonNull(token, "token");
        synchronized (lock) {
            Instant now = now();
            reclaimExpired(now);
            StoredInput input = requireClaim(token, null, now);
            resetClaim(input, retryable ? InputStatus.PENDING : InputStatus.FAILED);
        }
    }

    @Override
    public List<PendingInput> listPending(SessionId id) {
        synchronized (lock) {
            requireSession(id);
            reclaimExpired(now());
            return inputs.values().stream()
                    .filter(input -> input.status == InputStatus.PENDING
                            && input.pending.sessionId().equals(id))
                    .map(input -> input.pending).toList();
        }
    }

    private void validateFork(SessionCommit command) {
        SessionSnapshot parent = requireSession(command.header().parentSessionId()).snapshot();
        Session source = parent.session();
        if (parent.status() != SessionStatus.IDLE || source.hasOpenTurn()) {
            throw new IllegalStateException("fork requires an idle parent with no open turn");
        }
        List<SessionEvent> fixed = command.fixedEvents();
        if (!(fixed.get(fixed.size() - 1).data() instanceof TurnEnd)) {
            throw new IllegalArgumentException("fork prefix must end at a closed-turn boundary");
        }
        if (fixed.size() > source.events().size()
                || !source.events().subList(0, fixed.size()).equals(fixed)) {
            throw new IllegalArgumentException("fixedEvents must be the parent's unchanged log prefix");
        }
    }

    private void validateConsumption(SessionCommit command, StoredInput input, Session previous,
                                     SessionStatus next) {
        if (next != SessionStatus.RUNNING) {
            throw new IllegalArgumentException("input consumption requires RUNNING status");
        }
        boolean openTurn = previous != null && previous.hasOpenTurn();
        boolean batchTurnStart = false;
        int matchingMessages = 0;
        for (NewSessionEvent event : command.events()) {
            if (event.data() instanceof TurnStart) {
                openTurn = true;
                batchTurnStart = true;
            } else if (event.data() instanceof TurnEnd) {
                openTurn = false;
                batchTurnStart = false;
            } else if (event.data() instanceof UserMessageEvent user
                    && user.inputId().equals(input.pending.id().value())) {
                if (event.ignorable() || event.surface() == null
                        || !(event.surface().op() instanceof SurfaceOp.SurfaceAppend)) {
                    throw new IllegalArgumentException("consumed user/message must append to the visible surface");
                }
                if (!openTurn || (input.pending.target() == InboxTarget.NEXT_TURN && !batchTurnStart)) {
                    throw new IllegalArgumentException("turn/start must precede the consumed user/message");
                }
                if (!input.pending.item().content().equals(user.content())) {
                    throw new IllegalArgumentException("consumed input content must match user/message");
                }
                matchingMessages++;
            }
        }
        if (matchingMessages != 1) {
            throw new IllegalArgumentException("consumption requires exactly one matching user/message");
        }
    }

    private StoredSession requireSession(SessionId id) {
        Objects.requireNonNull(id, "id");
        StoredSession stored = sessions.get(id);
        if (stored == null) {
            throw new NoSuchElementException("unknown session: " + id);
        }
        return stored;
    }

    private StoredInput requireClaim(ClaimToken token, InputId inputId, Instant now) {
        Objects.requireNonNull(token, "token");
        StoredInput input = claims.get(token);
        if (input == null || input.status != InputStatus.IN_PROGRESS
                || !token.equals(input.token) || !input.leaseUntil.isAfter(now)
                || (inputId != null && !input.pending.id().equals(inputId))) {
            throw new IllegalStateException("claim is unknown, expired or no longer active");
        }
        return input;
    }

    private void reclaimExpired(Instant now) {
        for (StoredInput input : inputs.values()) {
            if (input.status == InputStatus.IN_PROGRESS && !input.leaseUntil.isAfter(now)) {
                resetClaim(input, InputStatus.PENDING);
            }
        }
    }

    private void resetClaim(StoredInput input, InputStatus next) {
        claims.remove(input.token);
        input.status = next;
        input.token = null;
        input.leaseUntil = null;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock.now()");
    }

    private record StoredSession(SessionSnapshot snapshot, List<UsageRecord> usage, List<EffectIntent> effects) {
    }

    private enum InputStatus { PENDING, IN_PROGRESS, CONSUMED, FAILED }

    private static final class StoredInput {
        private final PendingInput pending;
        private InputStatus status = InputStatus.PENDING;
        private ClaimToken token;
        private int attempts;
        private Instant leaseUntil;

        private StoredInput(PendingInput pending) {
            this.pending = pending;
        }
    }
}
