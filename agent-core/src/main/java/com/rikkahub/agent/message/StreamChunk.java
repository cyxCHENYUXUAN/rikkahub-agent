package com.rikkahub.agent.message;

import java.util.Objects;

/**
 * Normalized streaming output. Chunks are ephemeral: they go to in-process
 * listeners (and SSE) but are never written to the session event log one by one.
 */
public sealed interface StreamChunk
        permits StreamChunk.TextDelta, StreamChunk.ReasoningDelta, StreamChunk.ToolCallDelta,
                StreamChunk.Usage, StreamChunk.Finished, StreamChunk.Failed, StreamChunk.Cancelled {

    record TextDelta(String text) implements StreamChunk {
        public TextDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    record ReasoningDelta(String text) implements StreamChunk {
        public ReasoningDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Tool call fragments are keyed by {@code index}; a fragment may carry only
     * part of the arguments. The assembler concatenates fragments in arrival order.
     */
    record ToolCallDelta(int index, String callId, String toolName, String argumentsFragment)
            implements StreamChunk {
        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
        }
    }

    record Usage(TokenUsage usage) implements StreamChunk {
        public Usage {
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Finished(FinishReason reason) implements StreamChunk {
        public Finished {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Failed(String code, String message, boolean retryable) implements StreamChunk {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    record Cancelled() implements StreamChunk {
    }
}
