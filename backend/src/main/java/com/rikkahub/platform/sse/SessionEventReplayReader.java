package com.rikkahub.platform.sse;

import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Each snapshot/page releases its transaction and connection before network
 * I/O.
 */
final class SessionEventReplayReader {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;

    SessionEventReplayReader(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
    }

    long highWater(SessionId session) {
        return transactions.read(() -> jdbc.sql("SELECT last_seq FROM agent_sessions WHERE id=:id")
                .param("id", session.value()).query(Long.class).optional()
                .orElseThrow(() -> new NoSuchElementException("session not found: " + session)));
    }

    List<SessionEvent> page(SessionId session, long after, long highWater) {
        List<SessionEvent> events = transactions.read(() -> jdbc.sql("SELECT * FROM session_events "
                + "WHERE session_id=:id AND seq>:after AND seq<=:high ORDER BY seq LIMIT 256")
                .param("id", session.value()).param("after", after).param("high", highWater)
                .query((row, index) -> codec.readEvent(row)).list());
        long expected = after;
        for (SessionEvent event : events) {
            if (event.seq() != ++expected)
                throw new IllegalStateException("session replay log has a gap");
        }
        if (events.isEmpty() && after < highWater)
            throw new IllegalStateException("session replay log has a gap");
        return events;
    }
}
