package com.rikkahub.agent.session.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One durable fact in a session log. The discriminator is explicit so persisted
 * JSON never depends on Java class names.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TurnStart.class, name = "turn/start"),
        @JsonSubTypes.Type(value = TurnEnd.class, name = "turn/end"),
        @JsonSubTypes.Type(value = StepStart.class, name = "step/start"),
        @JsonSubTypes.Type(value = StepEnd.class, name = "step/end"),
        @JsonSubTypes.Type(value = UserMessageEvent.class, name = "user/message"),
        @JsonSubTypes.Type(value = SystemMessageEvent.class, name = "system/message"),
        @JsonSubTypes.Type(value = AssistantMessageEvent.class, name = "assistant/message"),
        @JsonSubTypes.Type(value = AssistantAttemptEvent.class, name = "assistant/attempt"),
        @JsonSubTypes.Type(value = ToolCallEvent.class, name = "tool/call"),
        @JsonSubTypes.Type(value = ToolResultEvent.class, name = "tool/result"),
        @JsonSubTypes.Type(value = RequestHeaderEvent.class, name = "request/header"),
        @JsonSubTypes.Type(value = RequestContextEvent.class, name = "request/context"),
        @JsonSubTypes.Type(value = ApprovalPolicyEvent.class, name = "approval/policy"),
        @JsonSubTypes.Type(value = ApprovalAskedEvent.class, name = "approval/asked"),
        @JsonSubTypes.Type(value = ApprovalDecidedEvent.class, name = "approval/decided"),
        @JsonSubTypes.Type(value = CompactionStartEvent.class, name = "compaction/start"),
        @JsonSubTypes.Type(value = CompactionSummaryEvent.class, name = "compaction/summary"),
        @JsonSubTypes.Type(value = CompactionEndEvent.class, name = "compaction/end"),
        @JsonSubTypes.Type(value = BranchSwitchEvent.class, name = "branch/switch"),
        @JsonSubTypes.Type(value = SessionEndSeedEvent.class, name = "session/end-seed")
})
public sealed interface SessionEventPayload
        permits TurnStart, TurnEnd, StepStart, StepEnd,
                UserMessageEvent, SystemMessageEvent, AssistantMessageEvent, AssistantAttemptEvent,
                ToolCallEvent, ToolResultEvent, RequestHeaderEvent, RequestContextEvent,
                ApprovalPolicyEvent, ApprovalAskedEvent, ApprovalDecidedEvent,
                CompactionStartEvent, CompactionSummaryEvent, CompactionEndEvent, BranchSwitchEvent,
                SessionEndSeedEvent {
}
