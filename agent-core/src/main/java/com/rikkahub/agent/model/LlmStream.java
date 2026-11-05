package com.rikkahub.agent.model;

/**
 * A live model response. Subscribers receive chunks until the stream terminates
 * with exactly one of {@link StreamListener#onComplete()} or
 * {@link StreamListener#onFailure(LlmFailure)}; no callbacks are delivered after
 * termination or after {@link #close()}.
 */
public interface LlmStream extends AutoCloseable {
    /** Registers the listener and returns promptly; adapters own blocking I/O threads. */
    void subscribe(StreamListener listener);

    @Override
    default void close() {
    }
}
