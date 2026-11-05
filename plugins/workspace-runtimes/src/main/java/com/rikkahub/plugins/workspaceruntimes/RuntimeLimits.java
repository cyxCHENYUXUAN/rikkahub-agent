package com.rikkahub.plugins.workspaceruntimes;

/** Explicit per-environment resource budgets. Job budget includes retained history. */
public record RuntimeLimits(int maxProcesses, int maxTerminals, int maxJobs,
                            int maxFileBytes, int maxWalkEntries, int maxWalkDepth,
                            int outputMemoryBytes, long outputSpillBytes, long maxTotalSpillBytes) {
    public RuntimeLimits {
        if (maxProcesses < 1 || maxTerminals < 1 || maxJobs < 1 || maxFileBytes < 1
                || maxWalkEntries < 1 || maxWalkDepth < 1 || outputMemoryBytes < 4
                || outputSpillBytes < outputMemoryBytes || maxTotalSpillBytes < outputSpillBytes)
            throw new IllegalArgumentException("invalid runtime limits");
        if (maxProcesses > 128 || maxFileBytes > 16 * 1024 * 1024 || outputMemoryBytes > 4 * 1024 * 1024
                || maxWalkDepth > 128 || maxWalkEntries > 100000 || maxJobs > 10000 || maxTerminals > 128)
            throw new IllegalArgumentException("runtime limits exceed implementation ceilings");
    }
    public static RuntimeLimits defaults() {
        return new RuntimeLimits(8, 4, 64, 1048576, 10000, 32, 65536, 16L * 1048576, 256L * 1048576);
    }
}
