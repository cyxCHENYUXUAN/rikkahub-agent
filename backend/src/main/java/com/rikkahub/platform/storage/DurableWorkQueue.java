package com.rikkahub.platform.storage;

import java.time.*;
import java.util.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.session.EffectIntent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stored-work-first queue. A claim token fences every acknowledgement,
 * including late success.
 */
@Repository
public class DurableWorkQueue {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final Clock clock;

    public DurableWorkQueue(JdbcClient jdbc, TransactionRunner transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Work(String id, String sessionId, String kind, String payloadJson, String status, int attempts,
            String claimToken, String leaseUntil, String error, String createdAt) {
    }

    public void enqueueInCurrentTransaction(String table, String session, EffectIntent effect) {
        table = table(table);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("stored work requires its business transaction");
        boolean retrySafe = effect.kind().equals("worker.start") || effect.kind().equals("local.reply");
        String now = EventCodec.timestamp(clock.now());
        jdbc.sql("INSERT INTO " + table
                + "(id,session_id,kind,payload_json,retry_safe,available_at,created_at,updated_at) VALUES(:id,:session,:kind,:payload,:safe,:available,:now,:now)")
                .param("id", effect.id()).param("session", session).param("kind", effect.kind())
                .param("payload", effect.payloadJson())
                .param("safe", retrySafe ? 1 : 0).param("available", EventCodec.timestamp(effect.availableAt()))
                .param("now", now).update();
    }

    public Work claim(String table, Duration lease) {
        String name = table(table);
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("invalid effect lease");
        return transactions.write(() -> {
            Instant now = clock.now();
            Work candidate = jdbc
                    .sql("SELECT * FROM " + name
                            + " WHERE status='PENDING' AND available_at<=:now ORDER BY created_at,rowid LIMIT 1")
                    .param("now", EventCodec.timestamp(now)).query((r, n) -> row(r)).optional().orElse(null);
            if (candidate == null)
                return null;
            String token = UUID.randomUUID().toString();
            int changed = jdbc.sql("UPDATE " + name
                    + " SET status='RUNNING',attempts=attempts+1,claim_token=:token,lease_until=:until,updated_at=:now WHERE id=:id AND status='PENDING'")
                    .param("token", token).param("until", EventCodec.timestamp(now.plus(lease)))
                    .param("now", EventCodec.timestamp(now)).param("id", candidate.id()).update();
            if (changed != 1)
                throw new ConcurrentModificationException("work already claimed");
            return new Work(candidate.id(), candidate.sessionId(), candidate.kind(), candidate.payloadJson(), "RUNNING",
                    candidate.attempts() + 1,
                    token, EventCodec.timestamp(now.plus(lease)), candidate.error(), candidate.createdAt());
        });
    }

    public boolean settle(String table, Work work, boolean success, String error) {
        String name = table(table);
        return transactions.write(() -> jdbc.sql("UPDATE " + name
                + " SET status=CASE WHEN :success=1 THEN 'DONE' WHEN retry_safe=1 AND attempts<3 THEN 'PENDING' ELSE 'DEAD' END,"
                + "available_at=:next,claim_token=NULL,lease_until=NULL,error=:error,updated_at=:now WHERE id=:id AND status='RUNNING' AND claim_token=:token AND lease_until>:now")
                .param("success", success ? 1 : 0).param("next", EventCodec.timestamp(clock.now().plusSeconds(2)))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 2048)))
                .param("now", EventCodec.timestamp(clock.now()))
                .param("id", work.id()).param("token", work.claimToken()).update() == 1);
    }

    public void recover() {
        transactions.write(() -> {
            for (String name : List.of("effects", "outbox"))
                jdbc.sql("UPDATE " + name
                        + " SET status=CASE WHEN retry_safe=1 AND attempts<3 THEN 'PENDING' ELSE 'DEAD' END,"
                        + "claim_token=NULL,lease_until=NULL,error='lease_expired: inspect external state before retry',updated_at=:now WHERE status='RUNNING' AND lease_until<=:now")
                        .param("now", EventCodec.timestamp(clock.now())).update();
            return null;
        });
    }

    /**
     * Admission pressure is not an execution attempt and must not exhaust retries.
     */
    public boolean defer(String table, Work work) {
        String name = table(table);
        return transactions.write(() -> jdbc.sql("UPDATE " + name
                + " SET status='PENDING',attempts=attempts-1,available_at=:next,claim_token=NULL,lease_until=NULL,updated_at=:now WHERE id=:id AND status='RUNNING' AND claim_token=:token AND lease_until>:now")
                .param("next", EventCodec.timestamp(clock.now().plusSeconds(2)))
                .param("now", EventCodec.timestamp(clock.now()))
                .param("id", work.id()).param("token", work.claimToken()).update() == 1);
    }

    public List<Work> list(String table, boolean deadOnly) {
        String name = table(table);
        return transactions.read(() -> jdbc
                .sql("SELECT * FROM " + name + (deadOnly ? " WHERE status='DEAD'" : "")
                        + " ORDER BY created_at,rowid LIMIT 500")
                .query((r, n) -> row(r)).list());
    }

    /**
     * Human acknowledgement records inspection; it never dispatches or retries an
     * external effect.
     */
    public void acknowledge(String table, String id, String note) {
        String name = table(table);
        if (note == null || note.isBlank() || note.length() > 1024)
            throw new IllegalArgumentException("inspection note is required");
        transactions.write(() -> {
            int changed = jdbc.sql("UPDATE " + name
                    + " SET status='DONE',error=coalesce(error,'')||char(10)||:note,updated_at=:now WHERE id=:id AND status='DEAD'")
                    .param("note", "acknowledged: " + note).param("now", EventCodec.timestamp(clock.now()))
                    .param("id", id).update();
            if (changed != 1)
                throw new IllegalStateException("only dead work can be acknowledged");
            return null;
        });
    }

    private static Work row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Work(r.getString("id"), r.getString("session_id"), r.getString("kind"), r.getString("payload_json"),
                r.getString("status"),
                r.getInt("attempts"), r.getString("claim_token"), r.getString("lease_until"), r.getString("error"),
                r.getString("created_at"));
    }

    private static String table(String table) {
        if (!Set.of("effects", "outbox").contains(table))
            throw new IllegalArgumentException("unknown work queue");
        return table;
    }
}
