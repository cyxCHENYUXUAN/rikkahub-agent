package com.rikkahub.features.workspace.api;

import com.rikkahub.agent.session.SessionId;
import com.rikkahub.features.workspace.output.ToolOutputStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated bounded reads of durable tool output references. */
@RestController
@RequestMapping("/api/v1")
public class ToolOutputController {
    private final ToolOutputStore outputs;

    public ToolOutputController(ToolOutputStore outputs) {
        this.outputs = outputs;
    }

    @GetMapping("/sessions/{id}/tool-outputs")
    public ToolOutputStore.Usage usage(@PathVariable String id) {
        return outputs.usage(SessionId.of(id));
    }

    @GetMapping("/sessions/{id}/tool-outputs/{reference}")
    public ResponseEntity<byte[]> read(@PathVariable String id, @PathVariable String reference,
                                       @RequestParam(defaultValue = "0") long offset,
                                       @RequestParam(defaultValue = "262144") int limit) {
        var range = outputs.read(SessionId.of(id), reference, offset, limit);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "no-store")
                .header("X-Tool-Output-Total-Bytes", Long.toString(range.totalBytes()))
                .header("X-Tool-Output-Truncated", Boolean.toString(range.truncated()))
                .header("X-Tool-Output-Status", range.status())
                .body(range.bytes());
    }
}
