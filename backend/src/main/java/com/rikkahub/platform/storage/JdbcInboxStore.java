package com.rikkahub.platform.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.port.ClaimedInput;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.port.InboxStore;
import com.rikkahub.agent.port.PendingInput;
import com.rikkahub.agent.session.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable leases; only the commit service can consume an input. */
@Repository
public class JdbcInboxStore implements InboxStore {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final Clock clock;
    private final IdGenerator ids;
    private final int maxAttempts;
    private final ThreadLocal<Completion> completion = new ThreadLocal<>();

    public JdbcInboxStore(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
                          Clock clock, IdGenerator ids,
                          @Value("${rikkahub.recovery.max-inbox-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) { throw new IllegalArgumentException("max inbox attempts must be positive"); }
        this.jdbc = jdbc; this.transactions = transactions; this.codec = codec;
        this.clock = clock; this.ids = ids; this.maxAttempts = maxAttempts;
    }

    @Override public void enqueue(PendingInput input) {
        transactions.write(() -> {enqueueInCurrentTransaction(input);return null;});
    }

    /** Compose durable input capture with its idempotency response, without consuming it. */
    public void enqueueInCurrentTransaction(PendingInput input) {
        requireTransaction();
        Objects.requireNonNull(input, "input");
        String payload = codec.encode(input.item());
            var existing = jdbc.sql("SELECT * FROM inbox_items WHERE id=:id").param("id",input.id().value())
                    .query((rs,n) -> pending(rs)).optional();
            if (existing.isPresent()) {
                if (!existing.get().equals(input)) { throw new IllegalStateException("input id already exists"); }
                return;
            }
            String conversation = jdbc.sql("SELECT conversation_id FROM agent_sessions WHERE id=:id")
                    .param("id",input.sessionId().value()).query((rs,n) -> rs.getString(1)).optional()
                    .orElse(null);
            requireSession(input.sessionId());
            jdbc.sql("""
                    INSERT INTO inbox_items(id,session_id,conversation_id,target,payload_json,status,created_at)
                    VALUES(:id,:session,:conversation,:target,:payload,'PENDING',:at)
                    """).param("id",input.id().value()).param("session",input.sessionId().value())
                    .param("conversation",conversation).param("target",input.target().name())
                    .param("payload",payload).param("at",EventCodec.timestamp(input.createdAt())).update();
    }

    @Override public ClaimedInput claim(SessionId id, InboxTarget target, String owner, Duration lease) {
        if (owner == null || owner.isBlank() || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("claim requires an owner and positive lease");
        }
        return transactions.write(() -> {
            requireSession(id);
            Instant now = clock.now(); reclaim(id,now);
            String filter = target == null ? "" : " AND target=:target";
            var query = jdbc.sql("SELECT * FROM inbox_items WHERE session_id=:session AND status='PENDING'"
                    + filter + " ORDER BY created_at,rowid LIMIT 1").param("session",id.value());
            if (target != null) { query = query.param("target",target.name()); }
            var candidate = query.query((rs,n) -> pending(rs)).optional();
            if (candidate.isEmpty()) { return null; }
            PendingInput input = candidate.get();
            ClaimToken token = new ClaimToken(ids.newId("claim"));
            Instant until = now.plus(lease);
            int changed = jdbc.sql("""
                    UPDATE inbox_items SET status='IN_PROGRESS',claim_token=:token,lease_owner=:owner,
                    claimed_at=:at,lease_until=:until,attempts=attempts+1
                    WHERE id=:id AND status='PENDING'
                    """).param("token",token.value()).param("owner",owner).param("at",EventCodec.timestamp(now))
                    .param("until",EventCodec.timestamp(until)).param("id",input.id().value()).update();
            if (changed != 1) { throw new java.util.ConcurrentModificationException("input was already claimed"); }
            int attempts = jdbc.sql("SELECT attempts FROM inbox_items WHERE id=:id")
                    .param("id",input.id().value()).query(Integer.class).single();
            return new ClaimedInput(input.id(),id,input.target(),input.item(),token,attempts,now,until);
        });
    }

    @Override public void complete(ClaimToken token, InputId inputId) {
        Completion allowed = completion.get();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || allowed == null
                || !allowed.input().claimToken().equals(token) || !allowed.input().inputId().equals(inputId)) {
            throw new IllegalStateException("input consumption requires its session commit transaction");
        }
        int changed = jdbc.sql("""
                UPDATE inbox_items SET status='CONSUMED',consumed_at=:at,claim_token=NULL,
                claimed_at=NULL,lease_until=NULL,lease_owner=NULL
                WHERE id=:id AND session_id=:session AND status='IN_PROGRESS'
                  AND claim_token=:token AND lease_until>:at
                """).param("at",EventCodec.timestamp(allowed.at())).param("id",inputId.value())
                .param("session",allowed.session().value()).param("token",token.value()).update();
        if (changed != 1) { throw new IllegalStateException("input lease is no longer valid"); }
    }

    ClaimedInput currentClaim(SessionId session, InboxConsumption input, Instant at) {
        requireTransaction();
        return jdbc.sql("""
                SELECT * FROM inbox_items WHERE id=:id AND session_id=:session
                AND status='IN_PROGRESS' AND claim_token=:token AND lease_until>:at
                """).param("id",input.inputId().value()).param("session",session.value())
                .param("token",input.claimToken().value()).param("at",EventCodec.timestamp(at))
                .query((rs,n) -> new ClaimedInput(new InputId(rs.getString("id")),session,
                        InboxTarget.valueOf(rs.getString("target")),codec.decode(rs.getString("payload_json"),InboxItem.class),
                        input.claimToken(),rs.getInt("attempts"),Instant.parse(rs.getString("claimed_at")),
                        Instant.parse(rs.getString("lease_until")))).optional()
                .orElseThrow(() -> new IllegalStateException("input lease is missing, expired or belongs to another session"));
    }

    void completeInCommit(SessionId session, InboxConsumption input, Instant at) {
        requireTransaction();
        if (completion.get() != null) { throw new IllegalStateException("nested consumption is not supported"); }
        completion.set(new Completion(session,input,at));
        try { complete(input.claimToken(),input.inputId()); }
        finally { completion.remove(); }
    }

    @Override public void release(ClaimToken token, boolean retryable) {
        Objects.requireNonNull(token,"token");
        transactions.write(() -> {
            int changed = jdbc.sql("""
                    UPDATE inbox_items SET status=:status,claim_token=NULL,claimed_at=NULL,
                    lease_until=NULL,lease_owner=NULL WHERE claim_token=:token
                    AND status='IN_PROGRESS' AND lease_until>:at
                    """).param("status",retryable?"PENDING":"CANCELLED").param("token",token.value())
                    .param("at",EventCodec.timestamp(clock.now())).update();
            if (changed != 1) { throw new IllegalStateException("cannot release an expired or superseded lease"); }
            return null;
        });
    }

    @Override public List<PendingInput> listPending(SessionId id) {
        return transactions.write(() -> {
            requireSession(id); reclaim(id,clock.now());
            return jdbc.sql("SELECT * FROM inbox_items WHERE session_id=:id AND status='PENDING' ORDER BY created_at,rowid")
                    .param("id",id.value()).query((rs,n) -> pending(rs)).list();
        });
    }

    private void reclaim(SessionId session, Instant at) {
        jdbc.sql("""
                UPDATE inbox_items SET status=CASE WHEN attempts>=:max THEN 'CANCELLED' ELSE 'PENDING' END,
                claim_token=NULL,claimed_at=NULL,
                lease_until=NULL,lease_owner=NULL WHERE session_id=:session
                AND status='IN_PROGRESS' AND lease_until<=:at
                """).param("session",session.value()).param("at",EventCodec.timestamp(at)).param("max",maxAttempts).update();
    }
    private void requireSession(SessionId id) {
        if (jdbc.sql("SELECT count(*) FROM agent_sessions WHERE id=:id").param("id",id.value())
                .query(Integer.class).single() != 1) { throw new NoSuchElementException("session not found: "+id); }
    }
    private PendingInput pending(ResultSet rs) throws SQLException {
        return new PendingInput(new InputId(rs.getString("id")),SessionId.of(rs.getString("session_id")),
                InboxTarget.valueOf(rs.getString("target")),codec.decode(rs.getString("payload_json"),InboxItem.class),
                Instant.parse(rs.getString("created_at")));
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("session commit transaction required");
        }
    }
    private record Completion(SessionId session, InboxConsumption input, Instant at) {}
}
