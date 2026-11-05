package com.rikkahub.agent.port;

import java.util.Objects;

/** One bounded search result; URLs are returned as citations, not fetched automatically. */
public record SearchResult(String title, String url, String snippet, double score) {
    public SearchResult {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(snippet, "snippet");
    }
}
