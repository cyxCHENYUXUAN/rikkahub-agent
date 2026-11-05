package com.rikkahub.agent.scope;

/** Handle for a service registration. Closing it removes the service exactly once. */
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
