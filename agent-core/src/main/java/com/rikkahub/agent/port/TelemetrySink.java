package com.rikkahub.agent.port;

import java.util.Map;

/** Receives structured telemetry events; implementations must not throw. */
public interface TelemetrySink {
    void event(String name, Map<String, Object> attributes);
}
