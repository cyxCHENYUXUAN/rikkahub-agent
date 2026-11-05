package com.rikkahub.agent.orchestration;

import java.util.function.Function;
import com.rikkahub.agent.orchestration.Orchestration.State;

/** Atomically commits aggregate changes, effects and local replies, or rolls all back. */
public interface EngineStore {
    <T> T transact(Function<State,T> command);
}
