package com.rikkahub.agent.port;

import java.util.List;

/** Host-configured search adapter. Implementations must bound query and result sizes. */
public interface SearchPort {
    List<SearchResult> search(String query, int limit);
}
