package com.rikkahub.agent.session;

import java.util.List;
import java.util.Objects;

public record SurfaceMeta(SurfaceOp op, List<Long> sourceEventSeqs) {
    public SurfaceMeta {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(sourceEventSeqs, "sourceEventSeqs");
        sourceEventSeqs = List.copyOf(sourceEventSeqs);
    }
}
