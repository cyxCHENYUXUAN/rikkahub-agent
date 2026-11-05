package com.rikkahub.agent.session;

import tools.jackson.databind.JsonNode;

import com.rikkahub.agent.session.event.SessionEventPayload;

/**
 * Upgrades a stored payload to the current schema before it is deserialized.
 * Implementations chain from one schema version to the next.
 */
public interface EventUpcaster {
    SessionEventPayload upcast(int fromVersion, String type, JsonNode data);
}
