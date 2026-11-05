package com.rikkahub.agent.scope;

/** A service that releases resources when its registration is rolled back. */
@FunctionalInterface
public interface Disposable {
    void dispose();
}
