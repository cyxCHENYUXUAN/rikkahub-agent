package com.rikkahub.agent.port;

import java.util.List;
import java.time.Duration;

import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.session.ClaimToken;

/** Persistence port for inputs waiting to be consumed by the agent loop. */
public interface InboxStore {

    void enqueue(PendingInput input);

    /** Claims the oldest available input; null means no input is available. */
    default ClaimedInput claim(SessionId id, String owner, Duration lease) {
        return claim(id, null, owner, lease);
    }

    /** A null target accepts either input target. Expired leases are reclaimed first. */
    ClaimedInput claim(SessionId id, InboxTarget target, String owner, Duration lease);

    /**
     * Transaction-internal hook only. Implementations must reject calls outside
     * the SessionCommit transaction; callers normally use InboxConsumption.
     */
    void complete(ClaimToken token, InputId inputId);

    /** Releases a current lease; non-retryable inputs become terminal. */
    void release(ClaimToken token, boolean retryable);

    List<PendingInput> listPending(SessionId id);
}
