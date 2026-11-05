package com.rikkahub.agent.session.event;

import java.util.List;
import java.util.Objects;

/** Records a branch change; message replacement is carried by surface events. */
public record BranchSwitchEvent(String targetNodeId, long fromSeq, long toSeq,
                                List<Long> shadowedSeqs) implements SessionEventPayload {
    public BranchSwitchEvent {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        shadowedSeqs = List.copyOf(Objects.requireNonNull(shadowedSeqs, "shadowedSeqs"));
    }
}
