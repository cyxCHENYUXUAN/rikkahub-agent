package com.rikkahub.agent.tool;

import java.util.Objects;

/** Decisions accumulate restrictions; an allow never overrides a prior ask or denial. */
public sealed interface ToolDecision permits ToolDecision.Allow, ToolDecision.Deny,
        ToolDecision.Ask, ToolDecision.Cancel {
    record Allow() implements ToolDecision {}

    record Deny(String reason) implements ToolDecision {
        public Deny { Objects.requireNonNull(reason, "reason"); }
    }

    record Ask(String reason) implements ToolDecision {
        public Ask { Objects.requireNonNull(reason, "reason"); }
    }

    record Cancel(String reason) implements ToolDecision {
        public Cancel { Objects.requireNonNull(reason, "reason"); }
    }
}
