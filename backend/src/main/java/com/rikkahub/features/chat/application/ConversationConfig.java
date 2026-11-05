package com.rikkahub.features.chat.application;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.rikkahub.agent.approval.ApprovalPolicy;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.config.ContextPolicy;
import com.rikkahub.agent.config.PermissionPreset;
import com.rikkahub.agent.config.ToolPolicy;
import com.rikkahub.agent.model.ModelRef;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;

/** Single construction point for the frozen per-turn chat configuration. */
public final class ConversationConfig {
    private ConversationConfig() {
    }

    public static AgentConfigSnapshot freeze(ConversationPolicyService.Effective effective, Clock clock) {
        AssistantProfile profile = effective.assistant();
        PermissionPreset preset = PermissionPreset.valueOf(effective.mode());
        ApprovalPolicy approval = ApprovalPolicy.valueOf(effective.approval());
        ToolPolicy tools = new ToolPolicy(Set.of(), true, approval, preset, effective.rules());
        return AgentConfigSnapshot.freeze(profile.version(),
                new ModelRef(profile.providerId(), profile.model()), profile.generation(),
                ContextPolicy.defaults(profile.contextWindowTokens()), approval, tools, List.of(),
                "chat", Duration.ofMinutes(5), 25, clock);
    }
}
