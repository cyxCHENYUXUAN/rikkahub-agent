package com.rikkahub.features.approvals.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import com.rikkahub.features.approvals.application.ApprovalApplicationService;
import com.rikkahub.features.approvals.application.ApprovalApplicationService.ApprovalView;
import com.rikkahub.platform.http.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Approval list and single-use decisions. A 202 accepts the decision; the
 * durable ApprovalDecidedEvent from the writer is the settlement contract.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalApplicationService approvals;

    public ApprovalController(ApprovalApplicationService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalView> list(@RequestParam String sessionId) {
        return approvals.list(sessionId);
    }

    @PostMapping("/{id}")
    public ResponseEntity<JsonNode> decide(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        return ApiResponses.response(approvals.decide(id, key, body));
    }
}
