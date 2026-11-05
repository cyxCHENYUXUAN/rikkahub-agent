package com.rikkahub.agent.loop;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.rikkahub.agent.api.Agent;
import com.rikkahub.agent.api.AgentHandle;
import com.rikkahub.agent.api.AgentId;
import com.rikkahub.agent.api.AgentOptions;
import com.rikkahub.agent.api.AgentRuntime;
import com.rikkahub.agent.api.AgentSpec;
import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.execution.ExecutionEnvironment;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionHeader;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.SessionSnapshot;
import com.rikkahub.agent.session.SessionStatus;

/** Owns its worker threads and agent scopes; injected host services are borrowed. */
public final class DefaultAgentRuntime implements AgentRuntime, AutoCloseable {
    private final RuntimeServices services;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-loop");
        t.setDaemon(true);
        return t;
    });
    private final Map<AgentId, LoopAgent> agents = new LinkedHashMap<>();
    private final Map<SessionId, LoopAgent> sessions = new LinkedHashMap<>();
    private boolean closed;

    public DefaultAgentRuntime(RuntimeServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    public AgentHandle create(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Set<InputId> inputIds = new HashSet<>();
        for (InboxItem input : spec.initialInputs()) {
            if (!inputIds.add(new InputId(input.id()))) {
                throw new IllegalArgumentException("duplicate initial input id");
            }
        }
        LoopAgent agent;
        synchronized (this) {
            ensureOpen();
            checkAvailable(spec.id(), spec.header().id());
            checkWorkspace(spec.header(), spec.environment());
            services.sessions().commit(new SessionCommit(spec.header().id(), 0, spec.header(),
                    List.of(), List.of(), List.of(), List.of(), null, null));
            agent = register(spec.id(), spec.header().id(), spec.config(), spec.environment());
        }
        try {
            for (InboxItem input : spec.initialInputs()) {
                agent.followup(input);
            }
        } catch (RuntimeException ex) {
            agent.close();
            throw ex;
        }
        return new AgentHandle(agent, agent);
    }

    public synchronized AgentHandle resume(SessionId sessionId, AgentOptions options) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(options, "options");
        ensureOpen();
        options.parentSignal().throwIfCancelled();
        AgentId id = new AgentId(services.ids().newId("agent"));
        checkAvailable(id, sessionId);
        SessionSnapshot snapshot = services.sessions().load(sessionId);
        if (snapshot.session().hasOpenTurn()) {
            throw new IllegalStateException("session has an open turn; recover it before resuming");
        }
        if (snapshot.status() == SessionStatus.ENDED) {
            throw new IllegalStateException("session has ended");
        }
        checkWorkspace(snapshot.session().header(), options.environment());
        LoopAgent agent = register(id, sessionId, options.config(), options.environment());
        agent.bindParent(options.parentSignal());
        // Resume does not automatically replay work that might have billed or caused effects.
        return new AgentHandle(agent, agent);
    }

    private LoopAgent register(AgentId id, SessionId sessionId, AgentConfigSnapshot config,
                               ExecutionEnvironment environment) {
        LoopAgent agent = new LoopAgent(id, sessionId, config, environment, services, workers, this::remove);
        agents.put(id, agent);
        sessions.put(sessionId, agent);
        return agent;
    }

    private synchronized void remove(LoopAgent agent) {
        agents.remove(agent.id(), agent);
        sessions.remove(agent.sessionId(), agent);
    }

    public synchronized Optional<Agent> find(AgentId id) {
        return Optional.ofNullable(agents.get(id));
    }

    public synchronized List<Agent> list() {
        return List.copyOf(agents.values());
    }

    public void close() {
        List<LoopAgent> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = List.copyOf(agents.values());
        }
        RuntimeException failure = null;
        try {
            for (LoopAgent agent : snapshot) {
                try {
                    agent.close();
                } catch (RuntimeException ex) {
                    if (failure == null) {
                        failure = ex;
                    } else {
                        failure.addSuppressed(ex);
                    }
                }
            }
        } finally {
            workers.shutdown();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("runtime is closed");
        }
    }

    private void checkAvailable(AgentId id, SessionId sessionId) {
        if (agents.containsKey(id) || sessions.containsKey(sessionId)) {
            throw new IllegalStateException("agent or session already has a live writer");
        }
    }

    private static void checkWorkspace(SessionHeader header, ExecutionEnvironment environment) {
        Path root = environment.workspace().root().toAbsolutePath().normalize();
        if (!header.cwd().toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("session cwd must be within its execution workspace");
        }
    }
}
