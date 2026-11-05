package com.rikkahub.testkit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.rikkahub.agent.approval.ApprovalPolicy;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.config.ContextPolicy;
import com.rikkahub.agent.config.PermissionPreset;
import com.rikkahub.agent.config.SystemPromptUpdatePolicy;
import com.rikkahub.agent.config.ToolPolicy;
import com.rikkahub.agent.model.GenerationOptions;
import com.rikkahub.agent.model.ModelRef;
import com.rikkahub.agent.model.RetryPolicy;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;

/**
 * Reusable test fixtures and factory methods for building test instances
 * without depending on external configuration or database servers.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static AgentConfigSnapshot createDefaultConfig() {
        return new AgentConfigSnapshot(
                1L,
                null,
                Instant.parse("2026-11-04T00:00:00Z"),
                new ModelRef("scripted-provider", "scripted-model-1"),
                new GenerationOptions(0.7, 0.95, 2048, List.of(), Map.of()),
                new ContextPolicy(8192, 6000, 1000, SystemPromptUpdatePolicy.REPLACE_IN_PLACE),
                ApprovalPolicy.ASK,
                new ToolPolicy(Set.of(), true, ApprovalPolicy.ASK, PermissionPreset.FULL_ACCESS, List.of()),
                List.of(),
                "default",
                Duration.ofSeconds(30),
                10
        );
    }

    public static Clock fixedClock(Instant instant) {
        return () -> instant;
    }

    public static Clock sequentialClock(Instant start, Duration step) {
        AtomicLong counter = new AtomicLong(0);
        return () -> start.plus(step.multipliedBy(counter.getAndIncrement()));
    }

    public static IdGenerator sequentialIdGenerator() {
        AtomicLong counter = new AtomicLong(1);
        return prefix -> prefix + "-" + counter.getAndIncrement();
    }

    public static RetryPolicy noRetryPolicy() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO);
    }
}
