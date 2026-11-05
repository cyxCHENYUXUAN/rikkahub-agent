package com.rikkahub.agent.message;

import java.util.Objects;

public record ImageBlock(String mediaType, String base64Data) implements ContentBlock {
    public ImageBlock {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(base64Data, "base64Data");
        if (!mediaType.startsWith("image/")) {
            throw new IllegalArgumentException("mediaType must be an image/* type: " + mediaType);
        }
    }
}
