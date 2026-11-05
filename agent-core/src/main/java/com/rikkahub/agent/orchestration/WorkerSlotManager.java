package com.rikkahub.agent.orchestration;

import java.util.Collection;
import com.rikkahub.agent.orchestration.Orchestration.*;

public final class WorkerSlotManager {
    private final int slots;
    public WorkerSlotManager(int slots) {
        if(slots<1||slots>32) throw new IllegalArgumentException("worker slots must be within 1..32");
        this.slots=slots;
    }
    public boolean available(Collection<Assignment> assignments, boolean human) {
        long running=assignments.stream().filter(a->a.status()==AssignmentStatus.RUNNING).count();
        return running < slots + (human?1:0);
    }
}
