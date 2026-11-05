package com.rikkahub.agent.loop;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import com.rikkahub.agent.api.*;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.context.CompactionService;
import com.rikkahub.agent.event.AgentEventSink;
import com.rikkahub.agent.model.*;
import com.rikkahub.agent.port.*;
import com.rikkahub.agent.tool.*;

/** Host services shared by agents; resource ownership remains with the host. */
public record RuntimeServices(SessionStore sessions,InboxStore inbox,LlmAdapter llm,ToolRegistry tools,
        ToolPipeline toolPipeline,AgentEventSink events,List<PreStepHook> preStepHooks,TurnStoppingHook turnStoppingHook,
        Clock clock,IdGenerator ids,RetryPolicy retryPolicy,Function<AgentContext,AgentConfigSnapshot> configResolver,
        ToolOutputLimiter outputLimiter, CompactionService compaction) {
    public RuntimeServices {
        Objects.requireNonNull(sessions);Objects.requireNonNull(inbox);Objects.requireNonNull(llm);Objects.requireNonNull(tools);
        Objects.requireNonNull(toolPipeline);Objects.requireNonNull(events);preStepHooks=List.copyOf(preStepHooks);
        Objects.requireNonNull(turnStoppingHook);Objects.requireNonNull(clock);Objects.requireNonNull(ids);Objects.requireNonNull(retryPolicy);
        Objects.requireNonNull(configResolver);Objects.requireNonNull(outputLimiter);
    }

    /** Backward-compatible constructor without host compaction. */
    public RuntimeServices(SessionStore sessions,InboxStore inbox,LlmAdapter llm,ToolRegistry tools,
            ToolPipeline pipeline,AgentEventSink events,List<PreStepHook> hooks,TurnStoppingHook stopping,
            Clock clock,IdGenerator ids,RetryPolicy retry,Function<AgentContext,AgentConfigSnapshot> configResolver,
            ToolOutputLimiter outputLimiter) {
        this(sessions,inbox,llm,tools,pipeline,events,hooks,stopping,clock,ids,retry,configResolver,
                outputLimiter,null);
    }

    public RuntimeServices(SessionStore sessions,InboxStore inbox,LlmAdapter llm,ToolRegistry tools,ToolPipeline pipeline,AgentEventSink events,
            List<PreStepHook> hooks,TurnStoppingHook stopping,Clock clock,IdGenerator ids,RetryPolicy retry){
        this(sessions,inbox,llm,tools,pipeline,events,hooks,stopping,clock,ids,retry,AgentContext::config,
                (session,call,result)->result,null);
    }
}
