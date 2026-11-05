package com.rikkahub.features.chat.application;

import java.util.ArrayList;
import java.util.List;

import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.api.AgentId;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.scope.AgentScope;
import com.rikkahub.agent.session.NewSessionEvent;
import com.rikkahub.agent.session.Session;
import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.SessionSnapshot;
import com.rikkahub.agent.session.SessionStatus;
import com.rikkahub.agent.session.event.CompactionEndEvent;
import com.rikkahub.agent.session.event.CompactionStartEvent;
import com.rikkahub.agent.session.event.TurnStart;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.SessionCommitService;
import org.springframework.stereotype.Service;

/** Idle-only manual compaction and abandon entry points. */
@Service
public final class ManualCompactionService {
    private final com.rikkahub.agent.port.SessionStore sessions;
    private final SessionCommitService commits;
    private final ConversationPolicyService policies;
    private final com.rikkahub.agent.context.CompactionService compaction;
    private final com.rikkahub.agent.port.Clock clock;

    public ManualCompactionService(com.rikkahub.agent.port.SessionStore sessions, SessionCommitService commits,
                                   ConversationPolicyService policies,
                                   com.rikkahub.agent.context.CompactionService compaction,
                                   com.rikkahub.agent.port.Clock clock) {
        this.sessions = sessions;
        this.commits = commits;
        this.policies = policies;
        this.compaction = compaction;
        this.clock = clock;
    }

    public Result compact(SessionId sessionId) {
        SessionSnapshot snapshot = sessions.load(sessionId);
        if (snapshot.session().hasOpenTurn() || snapshot.status() == SessionStatus.RUNNING) {
            throw new ApiFailure(409, "SESSION_BUSY", "Compaction requires an idle session.");
        }
        if (snapshot.status() != SessionStatus.IDLE && snapshot.status() != SessionStatus.CREATED) {
            throw new ApiFailure(409, "SESSION_NOT_IDLE", "The session is not idle.");
        }
        AgentConfigSnapshot config = ConversationConfig.freeze(policies.resolve(sessionId), clock);
        com.rikkahub.agent.context.CompactionExecution execution = compaction.compact(
                context(sessionId, config), snapshot.session().deriveMessages(), currentTurn(snapshot.session()), 1);
        if (execution == null) {
            throw new ApiFailure(409, "COMPACTION_NOT_NEEDED", "The context is below the compaction threshold.");
        }
        List<NewSessionEvent> events = new ArrayList<>();
        events.add(new NewSessionEvent(execution.start(), null, false));
        if (execution.summary() != null) {
            events.add(new NewSessionEvent(execution.summary(), null, false));
        }
        if (execution.replacement() != null) {
            events.add(new NewSessionEvent(execution.replacement(), execution.surface(), false));
        }
        events.add(new NewSessionEvent(execution.end(), null, false));
        commits.commit(new SessionCommit(sessionId, snapshot.session().lastSeq(), null, events,
                List.of(), List.of(), List.of(), null, null));
        if (!execution.succeeded()) {
            throw new ApiFailure(409, "COMPACTION_FAILED", execution.end().error());
        }
        return new Result(execution.start().compactionId(), true);
    }

    public void abandon(SessionId sessionId, long startSeq) {
        SessionSnapshot snapshot = sessions.load(sessionId);
        CompactionStartEvent start = null;
        boolean closed = false;
        for (var event : snapshot.session().events()) {
            if (event.seq() == startSeq && event.data() instanceof CompactionStartEvent value) {
                start = value;
            } else if (start != null && event.data() instanceof CompactionEndEvent end
                    && end.compactionId().equals(start.compactionId())) {
                closed = true;
            }
        }
        if (start == null) {
            throw new ApiFailure(404, "COMPACTION_NOT_FOUND", "No compaction start exists at this sequence.");
        }
        if (closed) {
            throw new ApiFailure(409, "COMPACTION_ALREADY_CLOSED", "The compaction is already closed.");
        }
        commits.commit(new SessionCommit(sessionId, snapshot.session().lastSeq(), null,
                List.of(new NewSessionEvent(new CompactionEndEvent(start.compactionId(), "abandoned"), null, false)),
                List.of(), List.of(), List.of(), null, null));
    }

    private AgentContext context(SessionId sessionId, AgentConfigSnapshot config) {
        return new AgentContext() {
            public AgentId agentId() { return new AgentId("manual-compaction"); }
            public SessionId sessionId() { return sessionId; }
            public AgentConfigSnapshot config() { return config; }
            public ExecutionEnvironment environment() { return null; }
            public AgentScope scope() { return null; }
            public CancellationToken cancellation() { return CancellationToken.none(); }
        };
    }

    private static int currentTurn(Session session) {
        int turn = 0;
        for (var event : session.events()) {
            if (event.data() instanceof TurnStart start) {
                turn = start.turn();
            }
        }
        return turn;
    }

    public record Result(String compactionId, boolean summarized) {}
}
