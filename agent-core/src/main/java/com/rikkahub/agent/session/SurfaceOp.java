package com.rikkahub.agent.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Describes how an event mutates the derived message surface. The JSON
 * discriminator is explicit so persisted sessions do not depend on class names.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SurfaceOp.SurfaceAppend.class, name = "append"),
        @JsonSubTypes.Type(value = SurfaceOp.SurfaceReplace.class, name = "replace")
})
public sealed interface SurfaceOp permits SurfaceOp.SurfaceAppend, SurfaceOp.SurfaceReplace {

    /** Appends the event's projected message to the surface. */
    record SurfaceAppend() implements SurfaceOp {
    }

    /** Replaces the surface entries whose source event seq lies in the closed range. */
    record SurfaceReplace(long startSeq, long endSeq) implements SurfaceOp {
        public SurfaceReplace {
            if (startSeq > endSeq) {
                throw new IllegalArgumentException(
                        "startSeq must not exceed endSeq: " + startSeq + " > " + endSeq);
            }
        }
    }
}
