package com.rikkahub.agent.loop;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.rikkahub.agent.port.PendingInput;

/**
 * In-memory input queues for one running agent, split by delivery target.
 * {@code NEXT_TURN} inputs open a new turn; {@code NEXT_STEP} inputs are merged
 * into the next step of the current turn. All operations are thread-safe.
 */
public final class Inbox {
    private final ConcurrentLinkedQueue<PendingInput> turnInputs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingInput> stepInputs = new ConcurrentLinkedQueue<>();

    public void enqueue(PendingInput input) {
        Objects.requireNonNull(input, "input");
        queueFor(input).add(input);
    }

    public Optional<PendingInput> pollTurn() {
        return Optional.ofNullable(turnInputs.poll());
    }

    public Optional<PendingInput> pollStep() {
        return Optional.ofNullable(stepInputs.poll());
    }

    public boolean hasTurn() {
        return !turnInputs.isEmpty();
    }

    public boolean hasStep() {
        return !stepInputs.isEmpty();
    }

    public int size() {
        return turnInputs.size() + stepInputs.size();
    }

    public void clear() {
        turnInputs.clear();
        stepInputs.clear();
    }

    private ConcurrentLinkedQueue<PendingInput> queueFor(PendingInput input) {
        return switch (input.target()) {
            case NEXT_TURN -> turnInputs;
            case NEXT_STEP -> stepInputs;
        };
    }
}
