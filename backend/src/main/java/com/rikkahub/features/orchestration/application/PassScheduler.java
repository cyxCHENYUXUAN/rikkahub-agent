package com.rikkahub.features.orchestration.application;

import java.time.Duration;
import java.util.concurrent.*;
import com.rikkahub.agent.orchestration.Engine;
import com.rikkahub.agent.orchestration.Orchestration.Decision;
import com.rikkahub.platform.storage.DurableWorkQueue;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every pass drains persisted work before evaluating new intake. Never performs external sends. */
@Component
public final class PassScheduler implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(PassScheduler.class);
    private final OrchestrationApplicationService orchestration;
    private final SourceApplicationService sources;
    private final Engine engine;
    private final DurableWorkQueue queue;
    private final EvaluationService evaluations;
    private final boolean enabled;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"rikka-orchestration");t.setDaemon(true);return t;});
    private boolean closed;
    public PassScheduler(OrchestrationApplicationService orchestration,SourceApplicationService sources,Engine engine,
                         DurableWorkQueue queue,EvaluationService evaluations,@Value("${rikkahub.orchestration.scheduler-enabled:true}") boolean enabled) {
        this.orchestration=orchestration;this.sources=sources;this.engine=engine;this.queue=queue;this.enabled=enabled;
        this.evaluations=evaluations;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { if(enabled) executor.scheduleWithFixedDelay(()->{try{pass();}catch(RuntimeException failure){LOG.warn("Orchestration pass failed; persisted work remains available for inspection.");}},0,2,TimeUnit.SECONDS); }
    public synchronized void pass() {
        if(closed)return;
        queue.recover(); orchestration.poll();
        for(int i=0;i<64;i++) {
            var work=queue.claim("effects",Duration.ofMinutes(2)); if(work==null)break;
            try {
                if(!"worker.start".equals(work.kind())) { queue.settle("effects",work,false,"unsupported_effect_kind"); continue; }
                if(orchestration.dispatch(work.payloadJson())) queue.settle("effects",work,true,null);
                else queue.defer("effects",work);
            } catch(RuntimeException failure) { queue.settle("effects",work,false,"effect_dispatch_failed: inspect worker state"); }
        }
        for(int i=0;i<64;i++) {
            var work=queue.claim("outbox",Duration.ofSeconds(30)); if(work==null)break;
            queue.settle("outbox",work,"local.reply".equals(work.kind()),"local.reply".equals(work.kind())?null:"unsupported_outbox_kind");
        }
        evaluations.schedulePending();
        sources.intakeDue(orchestration);
    }
    @PreDestroy @Override public synchronized void close() { if(closed)return;closed=true;executor.shutdownNow();evaluations.close(); }
}
