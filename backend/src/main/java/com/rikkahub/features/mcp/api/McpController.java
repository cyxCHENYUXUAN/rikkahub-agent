package com.rikkahub.features.mcp.api;

import java.util.List;
import com.rikkahub.features.mcp.application.McpCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mcp/servers")
public final class McpController {
    private final McpCatalog catalog;
    public McpController(McpCatalog catalog){this.catalog=catalog;}
    @GetMapping public List<McpCatalog.Status> list(){return catalog.statuses();}
    @PostMapping("/{id}/connect") public McpCatalog.Status connect(@PathVariable String id){return catalog.connect(id);}
    @DeleteMapping("/{id}/connection") public ResponseEntity<Void> disconnect(@PathVariable String id){catalog.disconnect(id);return ResponseEntity.noContent().build();}
}
