package com.rikkahub.agent.tool;

import tools.jackson.databind.*;
import com.rikkahub.agent.api.*;
import com.rikkahub.agent.approval.*;
import com.rikkahub.agent.config.ToolPolicy;
import com.rikkahub.agent.message.ContentBlock;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Writer-side preflight/approval, bounded host workers, and ordered result projection. */
public final class DefaultToolPipeline implements ToolPipeline {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT=Duration.ofSeconds(120);
    private final ToolRegistry registry;
    private final ApprovalService approvals;
    private final ApprovalPolicy defaultPolicy;
    private final Clock clock;
    private final IdGenerator ids;
    private final ExecutorService executor;
    private final List<ToolHooks> hooks=new CopyOnWriteArrayList<>();
    private final List<ToolPostExecute> legacyPostHooks=new CopyOnWriteArrayList<>();
    private final Object activity=new Object();
    private final Map<String,Integer> activeBySession=new HashMap<>();

    public DefaultToolPipeline(ToolRegistry registry,ApprovalService approvals,ApprovalPolicy policy,Clock clock,IdGenerator ids,ExecutorService executor){
        this.registry=Objects.requireNonNull(registry);this.approvals=Objects.requireNonNull(approvals);this.defaultPolicy=Objects.requireNonNull(policy);
        this.clock=Objects.requireNonNull(clock);this.ids=Objects.requireNonNull(ids);this.executor=Objects.requireNonNull(executor);
    }
    public DefaultToolPipeline(ToolRegistry registry,ApprovalService approvals,ApprovalPolicy policy,Clock clock,IdGenerator ids,ExecutorService executor,ToolHooks hook){
        this(registry,approvals,policy,clock,ids,executor);addHooks(hook);
    }
    public void addHooks(ToolHooks hook){hooks.add(Objects.requireNonNull(hook));}
    public void addPreExecute(ToolPreExecute hook){
        addHooks(new ToolHooks(){public ToolDecision preExecute(ToolCall c,ToolRunContext ctx){
            var d=hook.decide(c,ctx);
            if(d instanceof PreExecuteDecision.Allow)return new ToolDecision.Allow();
            if(d instanceof PreExecuteDecision.Ask a)return new ToolDecision.Ask(a.reason());
            if(d instanceof PreExecuteDecision.Deny a)return new ToolDecision.Deny(a.reason());
            if(d instanceof PreExecuteDecision.Cancel a)return new ToolDecision.Cancel(a.reason());return null;
        }});
    }
    public void addGuard(ToolGuard guard){
        addHooks(new ToolHooks(){public ToolDecision guard(ToolCall c,ToolRunContext ctx){var d=guard.check(c,ctx);return d==null?null:d.<ToolDecision>map(ToolDecision.Deny::new).orElseGet(ToolDecision.Allow::new);}});
    }
    public void addPostExecute(ToolPostExecute hook){legacyPostHooks.add(Objects.requireNonNull(hook));}
    public ToolResult execute(ToolCall call,ToolScope scope,ToolRunContext context){return execute(call,new ToolExecutionScope(scope,context),context.signal());}
    @Override public ToolResult execute(ToolCall call,ToolExecutionScope scope,CancellationToken signal){return executeBatch(List.of(call),scope,signal).get(0);}

    @Override public List<ToolResult> executeBatch(List<ToolCall> calls,ToolExecutionScope scope,CancellationToken signal){
        Objects.requireNonNull(calls);Objects.requireNonNull(scope);Objects.requireNonNull(signal);
        if(calls.isEmpty())return List.of();
        ToolScope tools=scope.tools();ToolRunContext original=scope.context();
        if(!tools.sessionId().equals(original.sessionId())||!tools.agentId().equals(original.agentId())
                ||!tools.cwd().toAbsolutePath().normalize().equals(original.cwd().toAbsolutePath().normalize()))return errors(calls,"tool_scope_mismatch");
        String session=tools.sessionId();
        if(!enterCall(session))return errors(calls,"tool_busy: earlier work is still active");
        CancellationToken combined=CancellationToken.none();
        AutoCloseable caller=signal.onCancel(()->combined.cancel(signal.cause()));
        AutoCloseable other=original.signal()==signal?null:original.signal().onCancel(()->combined.cancel(original.signal().cause()));
        ToolRunContext context=new ToolRunContext(session,tools.agentId(),tools.cwd(),combined,original.attributes());
        List<ToolResult> results=new ArrayList<>();
        try {
            List<Prepared> prepared=new ArrayList<>();
            // Every approval checkpoint executes on this writer, before any worker submission.
            for(ToolCall call:calls)prepared.add(prepare(call,tools,context));
            boolean undrained=false;
            for(int start=0;start<prepared.size();){
                int end=start+1;
                if(prepared.get(start).safe())while(end<prepared.size()&&prepared.get(end).safe())end++;
                List<Work<ToolResult>> jobs=new ArrayList<>();
                for(int i=start;i<end;i++){
                    Prepared p=prepared.get(i);
                    if(undrained||combined.isCancelled()||p.refusal()!=null){jobs.add(null);continue;}
                    try{jobs.add(submit(session,combined,t->p.definition().execute(p.arguments(),new ToolRunContext(session,tools.agentId(),tools.cwd(),t,context.attributes()))));}
                    catch(RuntimeException e){jobs.add(null);prepared.set(i,p.refuse(ToolResult.error("tool_unavailable: execution capacity exhausted")));}
                }
                for(int i=start;i<end;i++){
                    Prepared p=prepared.get(i);Work<ToolResult> job=jobs.get(i-start);ToolResult result;
                    if(p.refusal()!=null)result=p.refusal();
                    else if(job==null)result=ToolResult.error(combined.isCancelled()?"tool_cancelled":"tool_not_drained");
                    else result=complete(p,job,context);
                    results.add(result);
                }
                for(var job:jobs)if(job!=null){job.closeRegistration();if(!job.terminated.isDone())undrained=true;}
                start=end;
            }
            return List.copyOf(results);
        }finally{closeQuietly(other);closeQuietly(caller);release(session);}
    }
    private Prepared prepare(ToolCall call,ToolScope scope,ToolRunContext context){
        ToolDefinition definition=registry.find(call.toolName(),scope).orElse(null);
        List<ToolHooks> current=List.copyOf(hooks);List<ToolPostExecute> legacy=List.copyOf(legacyPostHooks);
        Prepared prepared=new Prepared(call,definition,null,current,legacy,null,false);
        try{
            context.signal().throwIfCancelled();
            if(definition==null)return prepared.refuse(ToolResult.error("tool_not_found: "+call.toolName()));
            JsonNode args=call.argumentsJson().isBlank()?JSON.createObjectNode():JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.argumentsJson());
            if(args==null||!args.isObject())return prepared.refuse(ToolResult.error("tool_invalid_arguments: expected object"));
            prepared=new Prepared(call,definition,args,current,legacy,null,false);
            List<String> asks=new ArrayList<>();ToolResult denied;
            Object policy=context.attributes().get("toolPolicy");
            if(policy instanceof ToolPolicy value){denied=decide(value.decide(call.toolName(),args),asks);if(denied!=null)return prepared.refuse(denied);}
            for(ToolHooks hook:current){denied=decide(hook.preExecute(call,context),asks);if(denied!=null)return prepared.refuse(denied);}
            for(ToolHooks hook:current){denied=decide(hook.guard(call,context),asks);if(denied!=null)return prepared.refuse(denied);}
            context.signal().throwIfCancelled();
            if(!asks.isEmpty()){
                denied=approve(call,String.join("; ",asks),context);if(denied!=null)return prepared.refuse(denied);
            }
            boolean safe=false;try{safe=definition.concurrencySafe(args);}catch(RuntimeException ignored){/* unknown means exclusive */}
            return new Prepared(call,definition,args,current,legacy,null,safe);
        }catch(ApprovalPersistenceFailure e){throw e;}
        catch(CancelledException|CancellationException e){return prepared.refuse(ToolResult.error("tool_cancelled"));}
        catch(RuntimeException e){return prepared.refuse(ToolResult.error("tool_invalid_arguments_or_guard: "+message(e)));}
    }
    private ToolResult approve(ToolCall call,String reason,ToolRunContext context){
        Object configured=context.attributes().get("approvalPolicy");ApprovalPolicy policy=configured instanceof ApprovalPolicy p?p:defaultPolicy;
        if(policy==ApprovalPolicy.NEVER)return ToolResult.error("approval_rejected: policy=never");
        int turn=context.attributes().get("turn") instanceof Number n?n.intValue():0;
        ApprovalRequest request=new ApprovalRequest(ids.newId("apr"),context.sessionId(),call.callId(),call.toolName(),call.argumentsJson(),reason,clock.now(),turn,clock.now().plusSeconds(120));
        Object value=context.attributes().get("approvalCheckpoint");ApprovalCheckpoint checkpoint=value instanceof ApprovalCheckpoint c?c:null;
        if(checkpoint!=null)checkpoint(()->checkpoint.asked(request));
        ApprovalOutcome outcome=approvals.request(request,policy,context.signal());
        if(context.signal().isCancelled())outcome=new ApprovalOutcome.Cancelled("turn cancelled");
        ApprovalOutcome finalOutcome=outcome;
        if(checkpoint!=null)checkpoint(()->checkpoint.decided(request,finalOutcome));
        if(outcome instanceof ApprovalOutcome.AllowedOnce)return null;
        if(outcome instanceof ApprovalOutcome.Rejected r)return ToolResult.error("approval_rejected: "+r.reason());
        if(outcome instanceof ApprovalOutcome.Cancelled r)return ToolResult.error("approval_cancelled: "+r.reason());
        return ToolResult.error("approval_unavailable");
    }
    private static void checkpoint(Runnable action){try{action.run();}catch(RuntimeException e){throw new ApprovalPersistenceFailure(e);}}
    private ToolResult complete(Prepared p,Work<ToolResult> job,ToolRunContext context){
        ToolResult result;
        try {
            Duration timeout=p.definition().timeout();if(timeout==null||timeout.isNegative()||timeout.isZero())timeout=DEFAULT_TIMEOUT;
            long remaining=nanos(timeout)-(System.nanoTime()-job.started);
            result=job.completion.get(Math.max(1,remaining),TimeUnit.NANOSECONDS);
            if(context.signal().isCancelled())return ToolResult.error("tool_cancelled");
            if(result==null)return ToolResult.error("tool_error: tool returned null");
        }catch(TimeoutException e){job.cancel(CancelCause.TIMEOUT);return ToolResult.error("tool_timeout: "+p.call().toolName());}
        catch(InterruptedException e){job.cancel(CancelCause.PARENT);Thread.currentThread().interrupt();return ToolResult.error("tool_cancelled: interrupted");}
        catch(CancellationException e){return ToolResult.error("tool_cancelled");}
        catch(ExecutionException e){return ToolResult.error("tool_error: "+message(e.getCause()));}
        try{
            ToolExecution execution=new ToolExecution(p.call(),job.startedAt,Duration.ofNanos(Math.max(0,System.nanoTime()-job.started)));
            for(ToolHooks hook:p.hooks())result=new ToolResult(Objects.requireNonNull(hook.projectContent(p.call(),result)),result.isError(),result.metadata());
            for(ToolHooks hook:p.hooks())hook.postExecute(p.call(),result,context);
            for(ToolPostExecute hook:p.legacy()){
                ToolResult next=Objects.requireNonNull(hook.afterExecute(execution,result,context));result=new ToolResult(next.content(),result.isError()||next.isError(),next.metadata());
            }
            List<ContentBlock> content=Objects.requireNonNull(p.definition().finalizeContent(execution,result));
            result=new ToolResult(content,result.isError(),result.metadata());
            Object limiter=context.attributes().get("outputLimiter");
            if(limiter instanceof ToolOutputLimiter output)result=Objects.requireNonNull(output.limit(context.sessionId(),p.call(),result));
            return result;
        }catch(RuntimeException e){return ToolResult.error("tool_output_failed: side effect is not retried");}
    }
    private static ToolResult decide(ToolDecision d,List<String> asks){
        if(d==null)return ToolResult.error("tool_guard_denied: no decision");
        if(d instanceof ToolDecision.Deny v)return ToolResult.error("tool_denied: "+v.reason());
        if(d instanceof ToolDecision.Cancel)return ToolResult.error("tool_cancelled");
        if(d instanceof ToolDecision.Ask v)asks.add(v.reason());return null;
    }
    private record Prepared(ToolCall call,ToolDefinition definition,JsonNode arguments,List<ToolHooks> hooks,List<ToolPostExecute> legacy,ToolResult refusal,boolean safe){
        Prepared refuse(ToolResult result){return new Prepared(call,definition,arguments,hooks,legacy,result,false);}
    }
    private static List<ToolResult> errors(List<ToolCall> calls,String error){return calls.stream().map(c->ToolResult.error(error)).toList();}
    private <T> Work<T> submit(String session,CancellationToken signal,Function<CancellationToken,T> action){
        Work<T> work=new Work<>(session,action);retain(session);work.registration=signal.onCancel(()->work.cancel(signal.cause()));
        try{executor.execute(work);return work;}catch(RuntimeException e){work.cancel(CancelCause.ERROR);work.closeRegistration();throw e;}
    }
    @Override public boolean awaitQuiescence(Duration timeout){return await(null,timeout);}
    @Override public boolean awaitQuiescence(String session,Duration timeout){return await(Objects.requireNonNull(session),timeout);}
    private boolean await(String session,Duration timeout){
        Objects.requireNonNull(timeout);if(timeout.isNegative())throw new IllegalArgumentException("negative timeout");
        long total=nanos(timeout),start=System.nanoTime();
        synchronized(activity){while(session==null?!activeBySession.isEmpty():activeBySession.containsKey(session)){
            long left=total-(System.nanoTime()-start);if(left<=0)return false;
            try{TimeUnit.NANOSECONDS.timedWait(activity,left);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        }return true;}
    }
    private boolean enterCall(String session){synchronized(activity){if(activeBySession.containsKey(session))return false;activeBySession.put(session,1);return true;}}
    private void retain(String session){synchronized(activity){activeBySession.merge(session,1,Integer::sum);}}
    private void release(String session){synchronized(activity){int remaining=activeBySession.get(session)-1;if(remaining==0)activeBySession.remove(session);else activeBySession.put(session,remaining);activity.notifyAll();}}
    private final class Work<T> implements Runnable {
        final String session;final Function<CancellationToken,T> action;final CancellationToken signal=CancellationToken.none();
        final CompletableFuture<T> completion=new CompletableFuture<>();final CompletableFuture<Void> terminated=new CompletableFuture<>();
        final long started=System.nanoTime();final Instant startedAt=clock.now();
        AutoCloseable registration;Thread runner;boolean finished;
        Work(String session,Function<CancellationToken,T> action){this.session=session;this.action=action;}
        public void run(){
            synchronized(this){if(finished)return;runner=Thread.currentThread();}
            T value=null;Throwable failure=null;
            try{signal.throwIfCancelled();value=action.apply(signal);}catch(Throwable e){failure=e;}
            finally{synchronized(this){runner=null;finished=true;}release(session);terminated.complete(null);}
            if(failure==null)completion.complete(value);else completion.completeExceptionally(failure);
        }
        void cancel(CancelCause cause){
            boolean neverStarted;
            synchronized(this){if(finished)return;completion.completeExceptionally(new CancellationException("cancelled"));neverStarted=runner==null;if(neverStarted)finished=true;else runner.interrupt();}
            if(neverStarted){release(session);terminated.complete(null);}signal.cancel(cause);
        }
        void closeRegistration(){closeQuietly(registration);}
    }
    private static long nanos(Duration d){try{return d.toNanos();}catch(ArithmeticException e){return Long.MAX_VALUE;}}
    private static String message(Throwable e){return e==null?"unknown":e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static void closeQuietly(AutoCloseable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){/* detach only */}}
}
