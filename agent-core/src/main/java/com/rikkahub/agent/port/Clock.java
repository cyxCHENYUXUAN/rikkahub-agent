package com.rikkahub.agent.port;

import java.time.Instant;

/** Injected time source; core code must not call {@link Instant#now()} directly. */
public interface Clock {
    Instant now();
}
