package com.rikkahub.features.orchestration.api;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.rikkahub.agent.orchestration.Orchestration.Eval;
import com.rikkahub.features.orchestration.application.OrchestrationApplicationService;
import com.rikkahub.features.orchestration.application.WebhookSignatureVerifier;
import com.rikkahub.platform.http.ApiFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Public ingress gateway for external webhooks.
 * Authentication relies on cryptographic webhook signatures (HMAC-SHA256).
 * All accepted events default to observe-only (Decision.OBSERVE) and require user mandate approval.
 */
@RestController
@RequestMapping("/api/v1/webhooks/in")
public final class ExternalWebhookController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final OrchestrationApplicationService orchestration;
    private final WebhookSignatureVerifier verifier;

    public ExternalWebhookController(OrchestrationApplicationService orchestration,
                                     WebhookSignatureVerifier verifier) {
        this.orchestration = orchestration;
        this.verifier = verifier;
    }

    @PostMapping(value = "/{provider}", consumes = {MediaType.ALL_VALUE})
    public ResponseEntity<?> receiveWebhook(@PathVariable String provider,
                                           @RequestBody(required = false) byte[] rawBody,
                                           @RequestHeader HttpHeaders headers) {
        if (!PROVIDER_PATTERN.matcher(provider).matches()) {
            throw new ApiFailure(400, "INVALID_PROVIDER", "Invalid provider identifier.");
        }
        byte[] bodyBytes = rawBody != null ? rawBody : new byte[0];
        String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);

        // 1. Feishu/Lark URL verification challenge handling
        if ("feishu".equalsIgnoreCase(provider) || "lark".equalsIgnoreCase(provider)) {
            try {
                JsonNode json = JSON.readTree(bodyString);
                if ("url_verification".equals(json.path("type").asString())) {
                    String challenge = json.path("challenge").asString();
                    return ResponseEntity.ok(Map.of("challenge", challenge));
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Validate cryptographic signature
        Map<String, String> headerMap = new HashMap<>();
        headers.forEach((k, v) -> {
            if (!v.isEmpty()) {
                headerMap.put(k, v.get(0));
            }
        });

        boolean verified = verifier.verify(provider, bodyBytes, headerMap);
        if (!verified) {
            throw new ApiFailure(401, "WEBHOOK_SIGNATURE_INVALID", "Invalid webhook signature or secret unconfigured.");
        }

        // 3. Extract event metadata and text
        String externalId = headers.getFirst("X-GitHub-Delivery");
        if (externalId == null || externalId.isBlank()) {
            externalId = headers.getFirst("X-Lark-Request-Nonce");
        }
        if (externalId == null || externalId.isBlank()) {
            externalId = UUID.randomUUID().toString();
        }

        String threadKey = provider + ":" + externalId;
        String text = bodyString;

        try {
            JsonNode json = JSON.readTree(bodyString);
            if (json.isObject()) {
                // If GitHub webhook
                if (headerMap.containsKey("x-github-event")) {
                    String eventType = headerMap.get("x-github-event");
                    String repo = json.path("repository").path("full_name").asString("repo");
                    String action = json.path("action").asString("");
                    threadKey = "github:" + repo;
                    text = "GitHub Event [" + eventType + (action.isEmpty() ? "" : "." + action) + "] on " + repo + ":\n" + bodyString;
                } else if (json.has("event")) {
                    // Feishu event
                    JsonNode evt = json.path("event");
                    if (evt.has("message")) {
                        String content = evt.path("message").path("content").asString();
                        text = "Feishu Message:\n" + content;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 4. Ingest into orchestration engine as an observe-only evaluation
        Eval eval = orchestration.ingest("webhook:" + provider, externalId, threadKey, text);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "accepted", true,
                "evalId", eval.id(),
                "decision", eval.decision().name()
        ));
    }
}
