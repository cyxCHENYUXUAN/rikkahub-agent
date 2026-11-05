package com.rikkahub.platform.storage;

import java.util.List;
import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SessionId;

/** Product projections join the session commit transaction through this platform port. */
public interface SessionProjection {
    void apply(SessionId sessionId, List<SessionEvent> events, ProjectionContext context);
}
