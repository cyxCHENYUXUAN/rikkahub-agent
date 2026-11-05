package com.rikkahub.features.orchestration.application;

import java.util.List;
import com.rikkahub.platform.storage.DurableWorkQueue;
import org.springframework.stereotype.Component;

/** Startup-only repair, after session repair and before the scheduler or HTTP admission. */
@Component
public final class Watchdog {
    private final OrchestrationApplicationService orchestration;
    private final DurableWorkQueue work;
    public Watchdog(OrchestrationApplicationService orchestration,DurableWorkQueue work) { this.orchestration=orchestration;this.work=work; }
    public List<String> recover() { work.recover();return orchestration.recover(); }
}
