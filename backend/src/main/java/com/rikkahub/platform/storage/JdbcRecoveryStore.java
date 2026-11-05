package com.rikkahub.platform.storage;

import java.time.Instant;
import java.util.List;
import com.rikkahub.agent.session.SessionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Recovery queries only for the tables actually present in the current migration set. */
@Repository
public class JdbcRecoveryStore {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    public JdbcRecoveryStore(JdbcClient jdbc, TransactionRunner transactions) { this.jdbc = jdbc; this.transactions = transactions; }

    public LeaseRecovery reclaimExpired(Instant at, int maxAttempts) {
        if (maxAttempts < 1) { throw new IllegalArgumentException("max inbox attempts must be positive"); }
        return transactions.write(() -> {
            int cancelled = reclaim(at, maxAttempts, true);
            int reset = reclaim(at, maxAttempts, false);
            return new LeaseRecovery(reset, cancelled);
        });
    }

    private int reclaim(Instant at, int maxAttempts, boolean cancel) {
        return jdbc.sql("""
                UPDATE inbox_items SET status=:status,claim_token=NULL,claimed_at=NULL,lease_until=NULL,lease_owner=NULL
                WHERE status='IN_PROGRESS' AND lease_until<=:at AND attempts
                """ + (cancel ? ">=:max" : "<:max"))
                .param("status", cancel ? "CANCELLED" : "PENDING").param("at", EventCodec.timestamp(at))
                .param("max", maxAttempts).update();
    }

    public List<SessionId> candidates() {
        return transactions.read(() -> jdbc.sql("""
                SELECT s.id FROM agent_sessions s WHERE s.open_turn=1 OR s.status='RUNNING'
                OR EXISTS (SELECT 1 FROM session_events a WHERE a.session_id=s.id AND a.event_type='approval/asked'
                  AND NOT EXISTS (SELECT 1 FROM session_events d WHERE d.session_id=s.id AND d.event_type='approval/decided'
                    AND d.seq>a.seq AND json_extract(d.data_json,'$.approvalId')=json_extract(a.data_json,'$.approvalId')))
                OR EXISTS (SELECT 1 FROM session_events c WHERE c.session_id=s.id AND c.event_type='compaction/start'
                  AND NOT EXISTS (SELECT 1 FROM session_events e WHERE e.session_id=s.id AND e.event_type='compaction/end'
                    AND e.seq>c.seq AND json_extract(e.data_json,'$.compactionId')=json_extract(c.data_json,'$.compactionId')))
                ORDER BY s.created_at,s.id
                """).query((row, n) -> SessionId.of(row.getString(1))).list());
    }

    public record LeaseRecovery(int reset, int cancelled) { }
}
