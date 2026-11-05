package com.rikkahub.agent.execution;

import java.util.List;

public interface JobManager extends AutoCloseable {
    String submit(JobSpec job);

    List<JobInfo> list();

    boolean cancel(String jobId);

    default ProcessOutput output(String jobId) {
        throw new UnsupportedOperationException("job output unavailable");
    }

    @Override
    default void close() { }
}
