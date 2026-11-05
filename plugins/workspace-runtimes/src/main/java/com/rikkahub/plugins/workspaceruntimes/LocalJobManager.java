package com.rikkahub.plugins.workspaceruntimes;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.execution.*;
import java.util.*;

/** Bounded job history; callers cannot evade its budget by finishing many tiny jobs. */
public final class LocalJobManager implements JobManager {
    private final LocalProcessSupervisor supervisor;
    private final int limit;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private boolean closed;
    LocalJobManager(LocalProcessSupervisor supervisor, RuntimeLimits limits) {
        this.supervisor = supervisor; this.limit = limits.maxJobs();
    }
    @Override public synchronized String submit(JobSpec spec) {
        if (closed) throw new IllegalStateException("job service closed");
        if (spec.name().isBlank() || spec.name().length() > 256) throw new IllegalArgumentException("invalid job name");
        if (jobs.size() >= limit) throw new IllegalStateException("job history limit reached");
        var running = supervisor.start(spec.command(), CancellationToken.none(), false);
        String id = UUID.randomUUID().toString();
        jobs.put(id, new Job(id, spec.name(), running));
        return id;
    }
    @Override public synchronized List<JobInfo> list() { return jobs.values().stream().map(Job::info).toList(); }
    private synchronized Job find(String id) {
        var job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("unknown job");
        return job;
    }
    @Override public ProcessOutput output(String id) { return find(id).running.output(); }
    @Override public boolean cancel(String id) {
        Job job = find(id);
        if (job.running.done()) return false;
        job.running.stop("CANCELLED");
        job.running.await();
        return true;
    }
    @Override public void close() {
        List<Job> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = List.copyOf(jobs.values());
            snapshot.forEach(job -> job.running.stop("CLOSED"));
        }
        snapshot.forEach(job -> job.running.await());
    }
    private record Job(String id, String name, LocalProcessSupervisor.Running running) {
        JobInfo info() {
            var code = running.exitCode();
            if (code.isEmpty()) return new JobInfo(id, name, "RUNNING", null);
            String reason = running.reason();
            String status = reason == null ? (code.getAsInt() == 0 ? "SUCCEEDED" : "FAILED") : switch (reason) {
                case "CANCELLED", "CLOSED", "INTERRUPTED" -> "CANCELLED";
                case "TIMED_OUT" -> "TIMED_OUT";
                default -> "FAILED";
            };
            return new JobInfo(id, name, status, code.getAsInt());
        }
    }
}
