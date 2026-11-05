package com.rikkahub.agent.event;

import com.rikkahub.agent.approval.ApprovalOutcome;
import com.rikkahub.agent.approval.ApprovalRequest;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.session.event.TurnEndReason;
import com.rikkahub.agent.tool.ToolCall;
import com.rikkahub.agent.tool.ToolResult;

import java.util.Objects;

/**
 * Ephemeral, process-local events emitted while an agent turn runs. Durable
 * state is carried by session events instead.
 */
public sealed interface AgentEvent
        permits AgentEvent.TurnStarted, AgentEvent.StepStarted, AgentEvent.AssistantDelta,
                AgentEvent.AssistantReasoning, AgentEvent.ToolCallStarted,
                AgentEvent.ToolResultReady, AgentEvent.ApprovalRequested,
                AgentEvent.ApprovalResolved, AgentEvent.UsageUpdated, AgentEvent.TurnEnded,
                AgentEvent.SessionCommitted, AgentEvent.Failure {

    record TurnStarted(String sessionId, int turn) implements AgentEvent {
        public TurnStarted {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record StepStarted(String sessionId, int turn, int step) implements AgentEvent {
        public StepStarted {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record AssistantDelta(String sessionId, int turn, int step, int blockIndex, String text)
            implements AgentEvent {
        public AssistantDelta {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(text, "text");
        }
    }

    record AssistantReasoning(String sessionId, int turn, int step, int blockIndex, String text)
            implements AgentEvent {
        public AssistantReasoning {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(text, "text");
        }
    }

    record ToolCallStarted(String sessionId, ToolCall call) implements AgentEvent {
        public ToolCallStarted {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(call, "call");
        }
    }

    record ToolResultReady(String sessionId, String callId, ToolResult result)
            implements AgentEvent {
        public ToolResultReady {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(result, "result");
        }
    }

    record ApprovalRequested(String sessionId, ApprovalRequest request) implements AgentEvent {
        public ApprovalRequested {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(request, "request");
        }
    }

    record ApprovalResolved(String sessionId, String approvalId, ApprovalOutcome outcome)
            implements AgentEvent {
        public ApprovalResolved {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    record UsageUpdated(String sessionId, int turn, int step, TokenUsage usage)
            implements AgentEvent {
        public UsageUpdated {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(usage, "usage");
        }
    }

    record TurnEnded(String sessionId, int turn, TurnEndReason reason) implements AgentEvent {
        public TurnEnded {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record SessionCommitted(String sessionId, long seq) implements AgentEvent {
        public SessionCommitted {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record Failure(String sessionId, String code, String message) implements AgentEvent {
        public Failure {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
