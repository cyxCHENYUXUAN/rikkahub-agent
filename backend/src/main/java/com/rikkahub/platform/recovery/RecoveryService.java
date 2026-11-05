package com.rikkahub.platform.recovery;

import java.util.*;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.message.ToolCallBlock;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.SessionStore;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.session.event.*;
import com.rikkahub.platform.storage.JdbcRecoveryStore;
import com.rikkahub.platform.storage.SessionCommitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Startup-only durable repair. It has no runtime, model, tool or effect executor dependency. */
@Service
public final class RecoveryService {
    private final JdbcRecoveryStore recovery;
    private final SessionStore sessions;
    private final SessionCommitService commits;
    private final Clock clock;
    private final int maxAttempts;

    public RecoveryService(JdbcRecoveryStore recovery, SessionStore sessions, SessionCommitService commits,
                           Clock clock, @Value("${rikkahub.recovery.max-inbox-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) { throw new IllegalArgumentException("max inbox attempts must be positive"); }
        this.recovery = recovery; this.sessions = sessions; this.commits = commits; this.clock = clock; this.maxAttempts = maxAttempts;
    }

    public RecoveryReport recover() {
        var leases = recovery.reclaimExpired(clock.now(), maxAttempts);
        int interrupted = 0, approvals = 0;
        for (SessionId id : recovery.candidates()) {
            SessionSnapshot snapshot = sessions.load(id);
            Session session = snapshot.session();
            TurnStart openTurn = null;
            Map<String, PendingCall> calls = new LinkedHashMap<>();
            Map<String, StepStart> steps = new LinkedHashMap<>();
            Map<String, ApprovalAskedEvent> asked = new LinkedHashMap<>();
            Map<String, CompactionStartEvent> compactions = new LinkedHashMap<>();
            for (SessionEvent event : session.events()) {
                if (event.data() instanceof TurnStart turn) { openTurn = turn; calls.clear(); steps.clear(); }
                else if (event.data() instanceof TurnEnd) { openTurn = null; calls.clear(); steps.clear(); }
                else if (event.data() instanceof AssistantMessageEvent assistant && openTurn != null) {
                    // The model's call blocks are durable before tool execution starts.
                    // A missing tool/call does not imply a tool result is unnecessary.
                    for (var block : assistant.content()) {
                        if (block instanceof ToolCallBlock call) { calls.putIfAbsent(call.callId(), new PendingCall(call.callId(), call.toolName())); }
                    }
                }
                else if (event.data() instanceof ToolCallEvent call && openTurn != null) {
                    calls.put(call.callId(), new PendingCall(call.callId(), call.toolName()));
                }
                else if (event.data() instanceof ToolResultEvent result) { calls.remove(result.callId()); }
                else if (event.data() instanceof StepStart step && openTurn != null) { steps.put(step.turn() + ":" + step.step(), step); }
                else if (event.data() instanceof StepEnd step) { steps.remove(step.turn() + ":" + step.step()); }
                if (event.data() instanceof ApprovalAskedEvent approval) { asked.put(approval.approvalId(), approval); }
                else if (event.data() instanceof ApprovalDecidedEvent decision) { asked.remove(decision.approvalId()); }
                else if (event.data() instanceof CompactionStartEvent start) { compactions.put(start.compactionId(), start); }
                else if (event.data() instanceof CompactionEndEvent end) { compactions.remove(end.compactionId()); }
            }
            List<NewSessionEvent> additions = new ArrayList<>();
            for (ApprovalAskedEvent approval : asked.values()) {
                additions.add(fact(new ApprovalDecidedEvent(approval.approvalId(), "unavailable", "Process restarted before approval was settled")));
            }
            for (CompactionStartEvent start : compactions.values()) {
                // Pair the crash residue so it cannot be mistaken for an in-flight compaction;
                // the paid summary is never replayed automatically.
                additions.add(fact(new CompactionEndEvent(start.compactionId(), "interrupted")));
            }
            SessionStatus next = snapshot.status();
            if (openTurn != null) {
                for (PendingCall call : calls.values()) {
                    long seq = session.lastSeq() + additions.size() + 1;
                    additions.add(new NewSessionEvent(new ToolResultEvent(call.callId(), call.toolName(),
                            List.of(new TextBlock("ABORTED: Process ended before a tool result was recorded")), true),
                            new SurfaceMeta(new SurfaceOp.SurfaceAppend(), List.of(seq)), false));
                }
                for (StepStart step : steps.values()) { additions.add(fact(new StepEnd(step.turn(), step.step()))); }
                additions.add(fact(new TurnEnd(openTurn.turn(), new TurnEndReason.Interrupted())));
                next = SessionStatus.INTERRUPTED;
                interrupted++;
            } else if (next == SessionStatus.RUNNING) {
                // A closed turn (including Shutdown) is never relabelled as a crash.
                next = SessionStatus.IDLE;
            }
            if (!additions.isEmpty() || next != snapshot.status()) {
                commits.commit(new SessionCommit(id, session.lastSeq(), null, additions, List.of(), List.of(), List.of(), null,
                        new SessionStatusTransition(snapshot.status(), next)));
            }
            approvals += asked.size();
        }
        return new RecoveryReport(leases.reset(), leases.cancelled(), interrupted, approvals);
    }

    private static NewSessionEvent fact(SessionEventPayload payload) { return new NewSessionEvent(payload, null, false); }
    private record PendingCall(String callId, String toolName) { }
}
