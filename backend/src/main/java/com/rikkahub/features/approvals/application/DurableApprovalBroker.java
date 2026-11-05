package com.rikkahub.features.approvals.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.approval.ApprovalBroker;
import com.rikkahub.agent.approval.ApprovalOutcome;
import com.rikkahub.agent.approval.ApprovalRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
/**
 * Fail-closed approval broker over the durable approvals projection.
 */
@Component
public final class DurableApprovalBroker implements ApprovalBroker {
    private final JdbcClient jdbc;
    private final com.rikkahub.agent.port.Clock clock;
    private final Map<String, CompletableFuture<ApprovalOutcome>> waiters = new ConcurrentHashMap<>();
    private final Map<String, String> settled = new ConcurrentHashMap<>();
    public DurableApprovalBroker(JdbcClient jdbc, com.rikkahub.agent.port.Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
    }

    /** Decisions wake only waiters registered before the CAS committed; see request(). */
    public void recordDecision(String id, String decision) {
        settled.put(id, decision);
        var waiter = waiters.remove(id);
        if (waiter != null) waiter.complete(outcome(decision));
    }

    private static ApprovalOutcome outcome(String decision) {
        if ("allowed-once".equals(decision)) return new ApprovalOutcome.AllowedOnce();
        return new ApprovalOutcome.Rejected(decision);
    }

    @Override public ApprovalOutcome request(ApprovalRequest request, CancellationToken signal) {
        Row row = row(request.id());
        if (row == null) return new ApprovalOutcome.Unavailable("approval record missing");
        String prior = settled.get(request.id());
        if (prior != null) return outcome(prior);
        if (!"PENDING".equals(row.status())) return new ApprovalOutcome.Unavailable("already settled: " + row.status());
        if (row.requested() != null) return staleOrWait(request, row);
        return waitForDecision(request, signal, row);
    }

    /**
     * A recorded decision that never reached the writer (process restart between
     * the HTTP CAS and the ApprovalDecidedEvent) must not authorize anything.
     * The approval waits out its remaining lifetime and fails closed.
     */
    private ApprovalOutcome staleOrWait(ApprovalRequest request, Row row) {
        if (row.expiresAt() != null && row.expiresAt().isBefore(clock.now())) {
            return new ApprovalOutcome.Unavailable("approval expired");
        }
        parkUntilExpiry(request);
        return new ApprovalOutcome.Unavailable("decision recorded but never committed by the writer");
    }

    private ApprovalOutcome waitForDecision(ApprovalRequest request, CancellationToken signal, Row row) {
        var future = new CompletableFuture<ApprovalOutcome>();
        waiters.put(request.id(), future);
        // Re-read after registration: a decision committed between the first read and
        // this registration completes the waiter through recordDecision's settled map.
        Row fresh = row(request.id());
        if (fresh == null || !"PENDING".equals(fresh.status()) || fresh.requested() != null
                || expired(fresh)) {
            waiters.remove(request.id(), future);
            if (fresh != null && fresh.requested() != null && settled.containsKey(request.id())) {
                return outcome(settled.get(request.id()));
            }
            return new ApprovalOutcome.Unavailable("approval no longer pending");
        }
        var registration = signal.onCancel(() -> future.complete(new ApprovalOutcome.Cancelled("turn cancelled")));
        try {
            return future.get(remaining(fresh), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            return new ApprovalOutcome.Unavailable("approval expired");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ApprovalOutcome.Cancelled("interrupted");
        } catch (java.util.concurrent.ExecutionException cancelled) {
            return new ApprovalOutcome.Cancelled("turn cancelled");
        } finally {
            try { registration.close(); }
            catch (Exception detached) { /* cancellation registration detach only */ }
            waiters.remove(request.id(), future);
        }
    }

    private void parkUntilExpiry(ApprovalRequest request) {
        long millis = Math.max(0, Duration.between(clock.now(), request.expiresAt()).toMillis());
        try { TimeUnit.MILLISECONDS.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private boolean expired(Row row) {
        return row.expiresAt() != null && row.expiresAt().isBefore(clock.now());
    }

    private long remaining(Row row) {
        long millis = Duration.between(clock.now(), row.expiresAt()).toMillis();
        return Math.max(0, millis);
    }

    /** Number of brokers parked at a connection-free wait point; test seam, no behavior. */
    public int waitingCount() {
        return waiters.size();
    }

    private Row row(String id) {
        return jdbc.sql("SELECT status, requested_decision, expires_at FROM approvals WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? null : Instant.parse(rs.getString(3))))
                .optional().orElse(null);
    }

    private record Row(String status, String requested, java.time.Instant expiresAt) {}
}
