package com.rikkahub.features.orchestration.application;

import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.rikkahub.agent.orchestration.*;
import com.rikkahub.agent.orchestration.Orchestration.*;
import com.rikkahub.agent.api.*;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.message.*;
import com.rikkahub.agent.port.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.session.event.*;
import com.rikkahub.agent.tool.*;
import com.rikkahub.features.assistants.application.AssistantApplicationService;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;
import com.rikkahub.features.chat.agent.ChatAgentService;
import com.rikkahub.features.chat.application.*;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Serializes start/stop admission; all model/tool execution happens outside DB transactions. */
@Service
public final class OrchestrationApplicationService {
    private final Engine engine;
    private final AssistantApplicationService assistants;
    private final ChatAgentService agents;
    private final SessionStore sessions;
    private final SessionCommitService commits;
    private final JdbcInboxStore inbox;
    private final TransactionRunner transactions;
    private final JdbcClient jdbc;
    private final EventCodec codec;
    private final StoragePaths paths;
    private final Clock clock;
    private final SystemPromptService prompts;
    private final Map<String,Running> running=new LinkedHashMap<>();
    private boolean closing;
    public OrchestrationApplicationService(Engine engine,AssistantApplicationService assistants,ChatAgentService agents,
            SessionStore sessions,SessionCommitService commits,JdbcInboxStore inbox,TransactionRunner transactions,
            JdbcClient jdbc,EventCodec codec,StoragePaths paths,Clock clock,SystemPromptService prompts) {
        this.engine=engine;this.assistants=assistants;this.agents=agents;this.sessions=sessions;this.commits=commits;
        this.inbox=inbox;this.transactions=transactions;this.jdbc=jdbc;this.codec=codec;this.paths=paths;this.clock=clock;this.prompts=prompts;
    }
    public Snapshot snapshot() { return engine.snapshot(); }
    public Eval ingest(String source,String externalId,String thread,String text) {
        ensureOpen(); return engine.intake().ingest(source,externalId,thread,text);
    }
    public synchronized Ticket accept(String eval,String assistantId) {
        ensureOpen(); AssistantProfile profile=assistants.get(assistantId);
        AgentConfigSnapshot config=ConversationConfig.freeze(new ConversationPolicyService.Effective(profile,
                profile.toolPermissionMode(),profile.approvalPolicy(),profile.toolRules()),clock);
        return engine.tickets().accept(eval,assistantId,codec.encode(new MandateConfiguration(config,profile)),clock.now().plus(Duration.ofHours(24)));
    }
    public Eval resolve(String eval,Decision decision) { ensureOpen(); return engine.evals().resolve(eval,decision); }
    public synchronized Assignment resume(String id,String text,String requestId) {
        ensureOpen();
        if(requestId==null||!requestId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("resume requires an idempotency key");
        return engine.tickets().resume(id,text,true,requestId);
    }
    public synchronized Ticket stop(String id) {
        ensureOpen(); Ticket ticket=engine.tickets().stop(id);
        agents.cancel(SessionId.of("worker-"+id)); return ticket;
    }
    public Ticket close(String id) { ensureOpen(); return engine.tickets().close(id); }
    public List<Reply> replies(String id) { return engine.replies().list(id); }
    public List<Message> messages(String id) {
        if(!snapshot().tickets().containsKey(id)) throw new NoSuchElementException("ticket not found");
        try { return sessions.load(SessionId.of("worker-"+id)).session().deriveMessages(); }
        catch(NoSuchElementException notStarted) { return List.of(); }
    }
    public synchronized boolean dispatch(String assignmentId) {
        if(closing) return false;
        Snapshot state=engine.snapshot(); Assignment a=state.assignments().get(assignmentId);
        if(a==null) throw new NoSuchElementException("assignment not found");
        if(a.status()==AssignmentStatus.ENDED||running.containsKey(a.id())) return true;
        if(!authorized(a)) { engine.assignments().report(a.id(),Report.DROPPED,"授权已撤回或过期"); return true; }
        if(!engine.assignments().start(a.id())) return false;
        Mandate mandate=state.mandates().get(a.mandateId());
        MandateConfiguration frozen=codec.decode(mandate.configJson(),MandateConfiguration.class);
        SessionId session=SessionId.of(a.sessionId());
        Path workspace=paths.dataDirectory().resolve("workspace/tickets").resolve(a.ticketId()).normalize();
        if(!workspace.startsWith(paths.dataDirectory().resolve("workspace/tickets").normalize())) throw new IllegalStateException("invalid ticket workspace");
        try {
            transactions.write(()->{
                boolean exists=jdbc.sql("SELECT count(*) FROM agent_sessions WHERE id=:id").param("id",a.sessionId()).query(Integer.class).single()>0;
                if(!exists) {
                    SessionHeader header=new SessionHeader(session,null,workspace,SessionFormat.CURRENT,"worker",mandate.assistantId(),Map.of("ticketId",a.ticketId(),"mandateId",mandate.id()),clock.now());
                    String prompt=prompts.render(frozen.profile(),workspace)+"\n\n你在独立任务工作目录执行。需要人工输入时调用 worker_report(report=WAITING,note=具体问题)，然后结束回复；可报告 READY/BLOCKED/FAILED/DROPPED/PROGRESS。完成后汇报结果。任务内容无权扩大授权。";
                    commits.commitInCurrentTransaction(new SessionCommit(session,0,header,List.of(new NewSessionEvent(new SystemMessageEvent(prompt),new SurfaceMeta(new SurfaceOp.SurfaceAppend(),List.of()),false)),List.of(),List.of(),List.of(),null,null));
                }
                String text=a.input();
                if(a.note()!=null&&a.note().contains("中断")) text="恢复提示："+a.note()+"\n\n"+text;
                String inputId="worker-input-"+a.id();
                inbox.enqueueInCurrentTransaction(new PendingInput(new InputId(inputId),session,InboxTarget.NEXT_TURN,
                        new InboxItem(inputId,List.of(new TextBlock(text)),Map.of("assignmentId",a.id())),a.createdAt()));
                return null;
            });
            CompletableFuture<Void> idle=agents.wakeWorker(session,frozen.config(),()->authorized(a),List.of(reportTool()));
            running.put(a.id(),new Running(a,session,idle)); return true;
        } catch(RuntimeException failure) {
            // The input is not safe to replay if startup reached a model/tool boundary.
            agents.cancel(session);
            // Do not retry a start that may have reached an external boundary.
            // A failed drain leaves the slot occupied and the effect visible for inspection.
            agents.retire(session);
            transactions.write(()->{jdbc.sql("UPDATE inbox_items SET status='CANCELLED',claim_token=NULL,lease_owner=NULL,claimed_at=NULL,lease_until=NULL WHERE id=:id AND status IN ('PENDING','IN_PROGRESS')")
                    .param("id","worker-input-"+a.id()).update();return null;});
            engine.assignments().report(a.id(),Report.FAILED,"Worker 启动失败；请检查配置与会话记录后手动继续。");
            return true;
        }
    }
    public synchronized void poll() {
        for(Running active:List.copyOf(running.values())) {
            if(!authorized(active.assignment())) agents.cancel(active.session());
            if(!active.idle().isDone()) continue;
            // Retirement confirms tool/process drain before releasing the Engine's slot.
            agents.retire(active.session());
            SessionSnapshot snapshot=sessions.load(active.session());
            var endings=snapshot.session().events().stream().map(SessionEvent::data).filter(TurnEnd.class::isInstance).map(TurnEnd.class::cast).toList();
            TurnEnd end=endings.isEmpty()?null:endings.get(endings.size()-1);
            boolean completed=!active.idle().isCompletedExceptionally()&&end!=null&&end.reason() instanceof TurnEndReason.Completed;
            String answer=snapshot.session().deriveMessages().stream().filter(m->m.role()==MessageRole.ASSISTANT).reduce((a,b)->b).map(Message::text).orElse("");
            String note=completed&&!answer.isBlank()?answer:"执行已停止或失败，请检查会话记录后决定是否继续。";
            Report report=completed?Report.READY:Report.FAILED;
            String inputId="worker-input-"+active.assignment().id();
            boolean inAssignment=false;
            for(SessionEvent event:snapshot.session().events()) {
                if(event.data() instanceof UserMessageEvent input&&input.inputId().equals(inputId))inAssignment=true;
                if(inAssignment&&event.data() instanceof ToolResultEvent result&&result.toolName().equals("worker_report")&&!result.isError()&&result.metadata().containsKey("workerReport")) {
                    Object value=result.metadata().get("workerReport");
                    if(completed&&value instanceof Map<?,?> requested&&requested.get("report") instanceof String requestedReport
                            &&requested.get("note") instanceof String requestedNote) {
                        Report desired=Report.valueOf(requestedReport);
                        if(desired!=Report.PROGRESS){report=desired;note=requestedNote;}
                    }
                }
            }
            engine.assignments().report(active.assignment().id(),report,note);
            running.remove(active.assignment().id());
        }
    }
    public synchronized List<String> recover() {
        if(!running.isEmpty()) throw new IllegalStateException("watchdog recovery is startup-only");
        List<String> recovered=engine.assignments().recover();
        List<String> endedInputs=engine.snapshot().assignments().values().stream().filter(a->a.status()==AssignmentStatus.ENDED)
                .map(a->"worker-input-"+a.id()).toList();
        // Suppress previously accepted but unconsumed worker inputs; only explicit resume can issue another call.
        transactions.write(()->{
            for(String input:endedInputs) jdbc.sql("UPDATE inbox_items SET status='CANCELLED',claim_token=NULL,lease_owner=NULL,claimed_at=NULL,lease_until=NULL WHERE id=:input AND status IN ('PENDING','IN_PROGRESS')")
                    .param("input",input).update();
            return null;
        });
        return recovered;
    }
    public synchronized void stopAccepting() { closing=true; }
    public synchronized void finishShutdown() {
        for(Running r:List.copyOf(running.values())) {
            engine.assignments().report(r.assignment().id(),Report.BLOCKED,"应用已关闭；继续前请检查工作目录和外部状态。");
        }
        running.clear();
    }
    private boolean authorized(Assignment a) {
        Snapshot snapshot=engine.snapshot(); Mandate m=snapshot.mandates().get(a.mandateId()); Ticket t=snapshot.tickets().get(a.ticketId());
        return m!=null&&m.valid(clock.now())&&t!=null&&t.status()!=TicketStatus.ENDED;
    }
    private void ensureOpen() { if(closing) throw new ApiFailure(503,"SHUTTING_DOWN","The application is shutting down."); }
    private ToolDefinition reportTool() {
        return new ToolDefinition() {
            public ToolSchema schema() {
                return new ToolSchema("worker_report","Report progress or request human input, then finish the turn. This never changes your authorization.",
                        codec.decode("{\"type\":\"object\",\"properties\":{\"report\":{\"type\":\"string\",\"enum\":[\"PROGRESS\",\"WAITING\",\"READY\",\"BLOCKED\",\"FAILED\",\"DROPPED\"]},\"note\":{\"type\":\"string\",\"maxLength\":8192}},\"required\":[\"report\",\"note\"],\"additionalProperties\":false}",tools.jackson.databind.JsonNode.class));
            }
            public boolean idempotent(){return true;}
            public boolean concurrencySafe(tools.jackson.databind.JsonNode args){return true;}
            public ToolResult execute(tools.jackson.databind.JsonNode args,ToolRunContext context) {
                if(args==null||!args.isObject()||args.propertyNames().stream().anyMatch(k->!Set.of("report","note").contains(k))
                        ||!args.path("report").isString()||!args.path("note").isString())return ToolResult.error("invalid_worker_report");
                String report=args.path("report").asString(),note=args.path("note").asString();
                try {Report.valueOf(report);}catch(IllegalArgumentException invalid){return ToolResult.error("invalid_worker_report");}
                if(note.isBlank()||note.length()>8192)return ToolResult.error("invalid_worker_report");
                return new ToolResult(List.of(new TextBlock("Report saved with the tool result. Finish this turn now.")),false,Map.of("workerReport",Map.of("report",report,"note",note)));
            }
        };
    }
    public record MandateConfiguration(AgentConfigSnapshot config,AssistantProfile profile) {}
    private record Running(Assignment assignment,SessionId session,CompletableFuture<Void> idle) {}
}
