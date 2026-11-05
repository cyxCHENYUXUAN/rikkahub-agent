package com.rikkahub.agent.session.event;

public record StepEnd(int turn, int step) implements SessionEventPayload {
}
