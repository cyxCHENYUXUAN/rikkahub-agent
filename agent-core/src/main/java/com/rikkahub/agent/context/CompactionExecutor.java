package com.rikkahub.agent.context;

import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.session.SurfaceMeta;
import com.rikkahub.agent.session.SurfaceOp;
import com.rikkahub.agent.session.event.CompactionEndEvent;
import com.rikkahub.agent.session.event.CompactionStartEvent;
import com.rikkahub.agent.session.event.CompactionSummaryEvent;
import com.rikkahub.agent.session.event.UserMessageEvent;

/** Builds compaction events without writing the store; failures never replace the surface. */
public final class CompactionExecutor {
    private CompactionExecutor() {
    }

    public static CompactionExecution success(String compactionId, int trigger, CompactionPlan plan,
                                              String summary, TokenUsage usage,
                                              String provider, String model, String replacementInputId) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.triggered()) {
            throw new IllegalArgumentException("compaction plan did not trigger");
        }
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        CompactionStartEvent start = new CompactionStartEvent(compactionId, trigger);
        CompactionSummaryEvent summaryEvent = new CompactionSummaryEvent(compactionId,
                plan.shadowedStartSeq(), plan.shadowedEndSeq(), plan.shadowedSeqs(),
                summary, Objects.requireNonNull(usage, "usage"), Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(model, "model"));
        UserMessageEvent replacement = new UserMessageEvent(replacementInputId,
                List.of(new TextBlock(summary)), "compaction");
        SurfaceMeta surface = new SurfaceMeta(new SurfaceOp.SurfaceReplace(
                plan.shadowedStartSeq(), plan.shadowedEndSeq()), plan.shadowedSeqs());
        return new CompactionExecution(start, summaryEvent, replacement, surface,
                new CompactionEndEvent(compactionId, null));
    }

    public static CompactionExecution failure(String compactionId, int trigger, String error) {
        Objects.requireNonNull(error, "error");
        return new CompactionExecution(new CompactionStartEvent(compactionId, trigger), null, null, null,
                new CompactionEndEvent(compactionId, error));
    }
}
