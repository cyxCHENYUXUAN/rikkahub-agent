package com.rikkahub.agent.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cordis-style service scope: providing a service is a side effect, closing the
 * scope rolls registrations back in reverse order, and child scopes are closed
 * with their parent. Lookups fall through to the parent chain.
 */
public final class AgentScope implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentScope.class);

    private final AgentScope parent;
    private final List<Entry> entries = new ArrayList<>();
    private final List<AgentScope> children = new ArrayList<>();
    private boolean closed;

    public AgentScope() {
        this(null);
    }

    private AgentScope(AgentScope parent) {
        this.parent = parent;
    }

    public synchronized <T> Registration provide(ServiceKey<T> key, T service) {
        if (closed) {
            throw new IllegalStateException("scope is already closed");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(service, "service");
        Entry entry = new Entry(key, service);
        entries.add(entry);
        return () -> remove(entry);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> find(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.key.equals(key)) {
                return Optional.of((T) entry.service);
            }
        }
        return parent == null ? Optional.empty() : parent.find(key);
    }

    public synchronized AgentScope child() {
        if (closed) {
            throw new IllegalStateException("scope is already closed");
        }
        AgentScope child = new AgentScope(this);
        children.add(child);
        return child;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        List<AgentScope> childrenToClose;
        List<Entry> entriesToClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            childrenToClose = new ArrayList<>(children);
            children.clear();
            entriesToClose = new ArrayList<>(entries);
            entries.clear();
        }
        for (int i = childrenToClose.size() - 1; i >= 0; i--) {
            childrenToClose.get(i).close();
        }
        for (int i = entriesToClose.size() - 1; i >= 0; i--) {
            dispose(entriesToClose.get(i));
        }
    }

    private synchronized void remove(Entry entry) {
        if (entries.remove(entry)) {
            dispose(entry);
        }
    }

    private static void dispose(Entry entry) {
        Object service = entry.service;
        try {
            if (service instanceof Disposable disposable) {
                disposable.dispose();
            } else if (service instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception ex) {
            LOG.warn("Failed to dispose service {}", entry.key.name(), ex);
        }
    }

    private static final class Entry {
        private final ServiceKey<?> key;
        private final Object service;

        private Entry(ServiceKey<?> key, Object service) {
            this.key = key;
            this.service = service;
        }
    }
}
