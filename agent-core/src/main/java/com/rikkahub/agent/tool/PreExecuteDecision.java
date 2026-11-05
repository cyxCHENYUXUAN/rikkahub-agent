package com.rikkahub.agent.tool;

import java.util.Objects;

/** Outcome of a pre-execute hook. Every decision is terminal except allow. */
public sealed interface PreExecuteDecision
        permits PreExecuteDecision.Allow, PreExecuteDecision.Deny,
                PreExecuteDecision.Cancel, PreExecuteDecision.Ask {

    record Allow() implements PreExecuteDecision {
    }

    record Deny(String reason) implements PreExecuteDecision {
        public Deny {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Cancel(String reason) implements PreExecuteDecision {
        public Cancel {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Ask(String reason) implements PreExecuteDecision {
        public Ask {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
