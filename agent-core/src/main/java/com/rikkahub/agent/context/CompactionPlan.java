package com.rikkahub.agent.context;

import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.message.Message;

/** A pure plan; the writer remains responsible for committing events and surface ops. */
public record CompactionPlan(boolean triggered, long shadowedStartSeq, long shadowedEndSeq,
                             List<Long> shadowedSeqs, List<Message> messagesToSummarize) {
    public CompactionPlan {
        shadowedSeqs = List.copyOf(Objects.requireNonNull(shadowedSeqs, "shadowedSeqs"));
        messagesToSummarize = List.copyOf(Objects.requireNonNull(messagesToSummarize, "messagesToSummarize"));
        if (triggered) {
            if (shadowedStartSeq < 1 || shadowedEndSeq < shadowedStartSeq) {
                throw new IllegalArgumentException("invalid compaction range");
            }
            if (shadowedSeqs.isEmpty() || messagesToSummarize.isEmpty()) {
                throw new IllegalArgumentException("triggered compaction needs content");
            }
        }
    }

    public static CompactionPlan none() {
        return new CompactionPlan(false, 0, 0, List.of(), List.of());
    }
}
