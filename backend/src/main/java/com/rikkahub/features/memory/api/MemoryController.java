package com.rikkahub.features.memory.api;

import java.util.List;
import java.util.Map;

import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.MemoryDocument;
import com.rikkahub.agent.port.MemoryHit;
import com.rikkahub.agent.port.MemoryStore;
import com.rikkahub.platform.http.ApiFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Local memory editing and retrieval. Files under data/memory remain the source of truth. */
@RestController
@RequestMapping("/api/v1")
public final class MemoryController {
    private final MemoryStore memory;
    private final com.rikkahub.features.memory.application.MemoryExtractionService extraction;
    private final Clock clock;

    public MemoryController(MemoryStore memory,
                            com.rikkahub.features.memory.application.MemoryExtractionService extraction,
                            Clock clock) {
        this.memory = memory;
        this.extraction = extraction;
        this.clock = clock;
    }

    @GetMapping("/memories")
    public List<MemoryDocument> list() {
        return memory.list();
    }

    @GetMapping("/memories/search")
    public List<MemoryHit> search(@RequestParam("q") String query,
                                  @RequestParam(defaultValue = "10") int limit) {
        return memory.search(query, limit);
    }

    @GetMapping("/memories/{id}")
    public MemoryDocument read(@PathVariable String id) {
        return memory.read(id).orElseThrow(() -> new ApiFailure(404, "MEMORY_NOT_FOUND",
                "The memory document was not found."));
    }

    @PutMapping("/memories/{id}")
    public MemoryDocument upsert(@PathVariable String id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()
                || body.propertyNames().stream().anyMatch(key -> !key.equals("title") && !key.equals("body"))) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Memory input contains unsupported fields.");
        }
        JsonNode title = body.get("title");
        JsonNode content = body.get("body");
        if (title == null || !title.isString() || title.stringValue().isBlank()
                || title.stringValue().length() > 256
                || content == null || !content.isString() || content.stringValue().length() > 262144) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Memory title and body are invalid.");
        }
        MemoryDocument document = new MemoryDocument(id, title.stringValue(), content.stringValue(), clock.now());
        memory.upsert(document);
        return document;
    }

    @DeleteMapping("/memories/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!memory.delete(id)) {
            throw new ApiFailure(404, "MEMORY_NOT_FOUND", "The memory document was not found.");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/memories/refresh")
    public Map<String, Object> refresh() {
        memory.refresh();
        return Map.of("refreshed", true);
    }

    @PostMapping("/memories/extract")
    public Map<String, Object> extract(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()
                || body.propertyNames().stream().anyMatch(key -> !key.equals("sessionId"))) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Memory extraction accepts only sessionId.");
        }
        JsonNode sessionId = body.get("sessionId");
        if (sessionId == null || !sessionId.isString()) {
            throw new ApiFailure(400, "INVALID_REQUEST", "sessionId is required.");
        }
        int candidates = extraction.extract(com.rikkahub.agent.session.SessionId.of(sessionId.stringValue()));
        return Map.of("candidates", candidates);
    }
}
