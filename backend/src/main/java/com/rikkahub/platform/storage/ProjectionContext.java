package com.rikkahub.platform.storage;

import java.time.Instant;
import com.rikkahub.agent.session.SessionHeader;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The existing transaction, not permission to open a nested transaction or execute effects. */
public record ProjectionContext(JdbcClient jdbc, SessionHeader header, long previousSeq,
                                long lastSeq, long previousTurnStartSeq, Instant committedAt) {}
