package com.rikkahub.agent.api;

/** Observer invoked just before a turn closes. */
public interface TurnStoppingHook {
    void onTurnStopping(TurnStopContext context);
}
