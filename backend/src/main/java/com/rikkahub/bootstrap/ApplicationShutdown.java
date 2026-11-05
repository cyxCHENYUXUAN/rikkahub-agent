package com.rikkahub.bootstrap;

import com.rikkahub.features.chat.agent.ChatAgentService;
import com.rikkahub.platform.concurrency.SessionCommandQueue;
import com.rikkahub.platform.sse.SseHub;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Stop paid work before the web server waits for long-lived SSE requests. */
@Component
public final class ApplicationShutdown implements ApplicationListener<ContextClosedEvent>, Ordered {
    private final ApplicationContext context;
    private final SessionCommandQueue commands;
    private final ChatAgentService agents;
    private final SseHub sse;
    private final com.rikkahub.features.orchestration.application.OrchestrationApplicationService orchestration;
    private final com.rikkahub.features.orchestration.application.PassScheduler scheduler;

    public ApplicationShutdown(ApplicationContext context, SessionCommandQueue commands,
                               ChatAgentService agents, SseHub sse,
                               com.rikkahub.features.orchestration.application.OrchestrationApplicationService orchestration,
                               com.rikkahub.features.orchestration.application.PassScheduler scheduler) {
        this.context = context;
        this.commands = commands;
        this.agents = agents;
        this.sse = sse;
        this.orchestration=orchestration;this.scheduler=scheduler;
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) return;
        commands.stopAccepting();
        scheduler.close();
        orchestration.stopAccepting();
        try {
            agents.close();
            orchestration.finishShutdown();
        } finally {
            try { commands.close(); }
            finally { sse.close(); }
        }
    }
}
