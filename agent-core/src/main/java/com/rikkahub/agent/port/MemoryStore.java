package com.rikkahub.agent.port;

import java.util.List;
import java.util.Optional;

/** Host-owned memory catalog; implementations must keep query and result sizes bounded. */
public interface MemoryStore {
    List<MemoryHit> search(String query, int limit);

    Optional<MemoryDocument> read(String id);

    List<MemoryDocument> list();

    void upsert(MemoryDocument document);

    boolean delete(String id);

    /** Rebuild any index from the editable source; implementations may no-op. */
    default void refresh() {
    }
}
