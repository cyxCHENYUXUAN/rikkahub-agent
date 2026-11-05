package com.rikkahub.agent.port;

import java.util.List;

import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionCommitResult;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.SessionSnapshot;
import com.rikkahub.agent.session.SessionSummary;

/** Persistence port for the append-only session log. */
public interface SessionStore {

    SessionSnapshot load(SessionId id);

    /**
     * Returns the continuous prefix ending at min(lastSeq, afterSeq + limit).
     * Session requires sequence 1 as its first event, so the snapshot includes
     * the prefix before afterSeq as well as at most limit additional events.
     * A future delta-only API needs a range DTO rather than SessionSnapshot.
     */
    SessionSnapshot loadRange(SessionId id, long afterSeq, int limit);

    /** Atomically creates, appends or forks, including usage, effects and input consumption. */
    SessionCommitResult commit(SessionCommit commit);

    List<SessionSummary> list();

}
