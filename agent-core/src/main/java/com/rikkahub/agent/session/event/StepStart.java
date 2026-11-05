package com.rikkahub.agent.session.event;

public record StepStart(int turn, int step) implements SessionEventPayload {
}
