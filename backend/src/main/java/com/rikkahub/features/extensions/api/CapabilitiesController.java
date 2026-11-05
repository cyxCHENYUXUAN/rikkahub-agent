package com.rikkahub.features.extensions.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rikkahub.plugins.workspaceruntimes.sandbox.SandboxCapabilities;
import com.rikkahub.plugins.workspaceruntimes.sandbox.SandboxConfig;
import com.rikkahub.plugins.workspaceruntimes.sandbox.SandboxDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports configuration-gated external capabilities without pretending they work. */
@RestController
@RequestMapping("/api/v1")
public final class CapabilitiesController {
    private final boolean searchConfigured;
    private final com.rikkahub.features.mcp.application.McpCatalog mcp;
    private final SandboxCapabilities sandboxCapabilities;

    public CapabilitiesController(@Value("${rikkahub.search.searxng-url:}") String searchUrl,
            com.rikkahub.features.mcp.application.McpCatalog mcp) {
        this.searchConfigured = searchUrl != null && !searchUrl.isBlank();
        this.mcp = mcp;
        this.sandboxCapabilities = SandboxDetector.detect(SandboxConfig.defaults());
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("search", searchConfigured);
        caps.put("mcp", mcp.available());
        caps.put("orchestration", true);
        caps.put("translation", false);
        caps.put("speech", false);
        caps.put("media", false);
        caps.put("pty", sandboxCapabilities.ptySupported());
        caps.put("sandbox", sandboxCapabilities.containerAvailable());
        return caps;
    }

    @GetMapping("/capabilities/sandbox")
    public SandboxCapabilities sandboxCapabilities() {
        return sandboxCapabilities;
    }
}
