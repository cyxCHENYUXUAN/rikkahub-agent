package com.rikkahub.agent.session.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

/**
 * Why a turn ended. The loop only produces completed/aborted/blocked/errored/
 * max_tokens; interrupted, forked and shutdown belong to recovery, fork and shutdown.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TurnEndReason.Completed.class, name = "completed"),
        @JsonSubTypes.Type(value = TurnEndReason.Aborted.class, name = "aborted"),
        @JsonSubTypes.Type(value = TurnEndReason.Blocked.class, name = "blocked"),
        @JsonSubTypes.Type(value = TurnEndReason.Errored.class, name = "errored"),
        @JsonSubTypes.Type(value = TurnEndReason.MaxTokens.class, name = "max_tokens"),
        @JsonSubTypes.Type(value = TurnEndReason.Interrupted.class, name = "interrupted"),
        @JsonSubTypes.Type(value = TurnEndReason.Forked.class, name = "forked"),
        @JsonSubTypes.Type(value = TurnEndReason.Shutdown.class, name = "shutdown")
})
public sealed interface TurnEndReason permits TurnEndReason.Completed, TurnEndReason.Aborted,
        TurnEndReason.Blocked, TurnEndReason.Errored, TurnEndReason.MaxTokens,
        TurnEndReason.Interrupted, TurnEndReason.Forked, TurnEndReason.Shutdown {

    record Completed() implements TurnEndReason {
    }

    record Aborted(String reason) implements TurnEndReason {
        public Aborted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Blocked(String reason) implements TurnEndReason {
        public Blocked {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Errored(String code, String message) implements TurnEndReason {
        public Errored {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    record MaxTokens() implements TurnEndReason {
    }

    record Interrupted() implements TurnEndReason {
    }

    record Forked() implements TurnEndReason {
    }

    record Shutdown() implements TurnEndReason {
    }
}
