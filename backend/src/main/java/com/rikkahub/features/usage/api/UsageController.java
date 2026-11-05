package com.rikkahub.features.usage.api;

import java.util.List;

import com.rikkahub.features.usage.application.UsageApplicationService;
import com.rikkahub.platform.http.ApiFailure;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/usage")
public final class UsageController {
    private final UsageApplicationService usage;

    public UsageController(UsageApplicationService usage) {
        this.usage = usage;
    }

    @GetMapping
    public UsageApplicationService.Totals totals(@RequestParam(required = false) String sessionId) {
        if (sessionId != null && !sessionId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiFailure(400, "INVALID_REQUEST", "The sessionId is invalid.");
        }
        return usage.totals(sessionId);
    }

    @GetMapping("/by-model")
    public List<UsageApplicationService.ModelUsage> byModel() {
        return usage.byModel();
    }

    @GetMapping("/by-day")
    public List<UsageApplicationService.DayUsage> byDay() {
        return usage.byDay();
    }
}
