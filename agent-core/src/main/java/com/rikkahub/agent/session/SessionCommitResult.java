package com.rikkahub.agent.session;

import java.util.Objects;

/** The committed tail and the independent product metadata version. */
public record SessionCommitResult(SessionId sessionId, long committedSeq, long lastSeq,
                                  long metadataVersion) {
    public SessionCommitResult {
        Objects.requireNonNull(sessionId, "sessionId");
        if (committedSeq < 0 || lastSeq < committedSeq || metadataVersion < 0) {
            throw new IllegalArgumentException("invalid commit result version or sequence");
        }
    }
}
