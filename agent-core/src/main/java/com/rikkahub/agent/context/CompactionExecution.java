package com.rikkahub.agent.context;

import java.util.Objects;

import com.rikkahub.agent.session.SurfaceMeta;
import com.rikkahub.agent.session.event.CompactionEndEvent;
import com.rikkahub.agent.session.event.CompactionStartEvent;
import com.rikkahub.agent.session.event.CompactionSummaryEvent;
import com.rikkahub.agent.session.event.UserMessageEvent;

/**
 * Pure description of a compaction commit. The writer owns event sequencing and
 * must append start, summary, replacement, end in that order.
 */
public record CompactionExecution(CompactionStartEvent start, CompactionSummaryEvent summary,
                                 UserMessageEvent replacement, SurfaceMeta surface,
                                 CompactionEndEvent end) {
    public CompactionExecution {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        boolean success = summary != null;
        if (success != (replacement != null) || success != (surface != null)) {
            throw new IllegalArgumentException("successful compaction needs summary and surface");
        }
    }

    public boolean succeeded() {
        return summary != null;
    }
}
