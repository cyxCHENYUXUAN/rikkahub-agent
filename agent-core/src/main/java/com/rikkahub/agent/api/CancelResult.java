package com.rikkahub.agent.api;

public sealed interface CancelResult
        permits CancelResult.Cancelled, CancelResult.NothingToCancel,
                CancelResult.AlreadyCancelling {

    /** The turn that was cancelled. */
    record Cancelled(long turn) implements CancelResult {
    }

    /** Nothing was running, so there was nothing to cancel. */
    record NothingToCancel() implements CancelResult {
    }

    /** A cancellation was already in progress. */
    record AlreadyCancelling() implements CancelResult {
    }
}
