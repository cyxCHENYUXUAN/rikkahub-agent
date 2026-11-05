package com.rikkahub.agent.port;

import com.rikkahub.agent.session.SessionId;

/** Generates opaque identifiers; {@code prefix} only namespaces the value. */
public interface IdGenerator {
    String newId(String prefix);

    default SessionId newSessionId() {
        return SessionId.of(newId("sess"));
    }
}
