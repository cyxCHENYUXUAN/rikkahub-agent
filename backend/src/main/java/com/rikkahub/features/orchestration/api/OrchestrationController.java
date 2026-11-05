package com.rikkahub.features.orchestration.api;

import java.util.*;
import com.rikkahub.agent.orchestration.Orchestration.*;
import com.rikkahub.features.orchestration.application.*;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.DurableWorkQueue;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** All mutation endpoints inherit the local Host/Origin/token guard. Webhooks are local intake only. */
@RestController
@RequestMapping("/api/v1/orchestration")
public final class OrchestrationController {
    private final OrchestrationApplicationService service;
    private final SourceApplicationService sources;
    private final DurableWorkQueue work;
    private final EvaluationService evaluations;
    private final OutboxDispatcherService outbox;
    private final SubagentCoordinatorService subagents;
    public OrchestrationController(OrchestrationApplicationService service,SourceApplicationService sources,
                                   DurableWorkQueue work,EvaluationService evaluations,OutboxDispatcherService outbox,
                                   SubagentCoordinatorService subagents) {
        this.service=service;this.sources=sources;this.work=work;
        this.evaluations=evaluations;this.outbox=outbox;
        this.subagents=subagents;
    }
    @GetMapping public Snapshot snapshot() { return service.snapshot(); }
    @GetMapping("/todos") public Map<String,Object> todos() {
        Snapshot s=service.snapshot();
        return Map.of("evaluations",s.evals().values().stream().filter(e->e.decision()==Decision.OBSERVE).toList(),
                "tickets",s.tickets().values().stream().filter(t->t.status()==TicketStatus.WAITING||t.status()==TicketStatus.READY).toList(),
                "effects",work.list("effects",true),"outbox",work.list("outbox",true));
    }
    @PostMapping("/triggers") @ResponseStatus(HttpStatus.ACCEPTED)
    public Eval ingest(@RequestBody JsonNode body) {
        fields(body,"source","externalId","threadKey","text");
        return service.ingest(text(body,"source",128),text(body,"externalId",256),text(body,"threadKey",256),text(body,"text",262144));
    }
    @PostMapping("/webhooks/{source}") @ResponseStatus(HttpStatus.ACCEPTED)
    public Eval webhook(@PathVariable String source,@RequestBody JsonNode body) {
        if(!source.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid source");
        fields(body,"externalId","threadKey","text");
        return service.ingest("webhook:"+source,text(body,"externalId",256),text(body,"threadKey",256),text(body,"text",262144));
    }
    @PostMapping("/evals/{id}/accept") public Ticket accept(@PathVariable String id,@RequestBody JsonNode body) {
        fields(body,"assistantId"); return conflict(()->service.accept(id,text(body,"assistantId",128)));
    }
    @PostMapping("/evals/{id}/resolve") public Eval resolve(@PathVariable String id,@RequestBody JsonNode body) {
        fields(body,"decision"); return conflict(()->service.resolve(id,Decision.valueOf(text(body,"decision",32))));
    }
    @PostMapping("/evals/{id}/evaluate") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Boolean> evaluate(@PathVariable String id,@RequestBody JsonNode body) {
        fields(body,"assistantId");return conflict(()->{evaluations.request(id,text(body,"assistantId",128));return Map.of("accepted",true);});
    }
    @PostMapping("/tickets/{id}/messages") public Assignment resume(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body) {
        fields(body,"text"); return conflict(()->service.resume(id,text(body,"text",262144),key));
    }
    @PostMapping("/tickets/{id}/stop") public Ticket stop(@PathVariable String id) { return conflict(()->service.stop(id)); }
    @PostMapping("/tickets/{id}/close") public Ticket close(@PathVariable String id) { return conflict(()->service.close(id)); }
    @GetMapping("/tickets/{id}/messages") public List<com.rikkahub.agent.message.Message> messages(@PathVariable String id) { return service.messages(id); }
    @GetMapping("/tickets/{id}/replies") public List<Reply> replies(@PathVariable String id) { return service.replies(id); }
    @GetMapping("/sources") public List<SourceApplicationService.Source> sources() { return sources.list(); }
    @PostMapping("/sources") @ResponseStatus(HttpStatus.CREATED)
    public SourceApplicationService.Source createSource(@RequestBody JsonNode body) {
        fields(body,"threadKey","text","intervalSeconds");
        JsonNode interval=body.get("intervalSeconds");
        if(interval==null||!interval.isIntegralNumber()||!interval.canConvertToLong())throw new IllegalArgumentException("invalid interval");
        return sources.create(text(body,"threadKey",256),text(body,"text",262144),interval.longValue());
    }
    @PostMapping("/work/{queue}/{id}/acknowledge") public ResponseEntity<Void> acknowledge(@PathVariable String queue,@PathVariable String id,@RequestBody JsonNode body) {
        fields(body,"note");return conflict(()->{work.acknowledge(queue,id,text(body,"note",1024));return ResponseEntity.noContent().build();});
    }
    @PostMapping("/outbox/dispatch") public Map<String, Object> dispatchOutbox() {
        int count = outbox.dispatchAll(32);
        return Map.of("dispatched", count);
    }
    @PatchMapping("/sources/{id}") public ResponseEntity<Void> source(@PathVariable String id,@RequestBody JsonNode body) {
        fields(body,"enabled");JsonNode enabled=body.get("enabled");
        if(enabled==null||!enabled.isBoolean())throw new IllegalArgumentException("invalid enabled value");
        sources.enabled(id,enabled.booleanValue());return ResponseEntity.noContent().build();
    }
    @PostMapping("/subagents/delegate") public SubagentCoordinatorService.SubagentTask delegateSubagent(@RequestBody JsonNode body) {
        fields(body, "parentSessionId", "assistantId", "instruction", "background");
        String parent = text(body, "parentSessionId", 128);
        String assistant = text(body, "assistantId", 128);
        String instruction = text(body, "instruction", 262144);
        String background = body.has("background") && !body.get("background").isNull() ? body.get("background").asString("") : null;
        return subagents.delegate(parent, assistant, instruction, background);
    }
    @GetMapping("/subagents/{taskId}") public SubagentCoordinatorService.SubagentTask getSubagentTask(@PathVariable String taskId) {
        return subagents.getTask(taskId);
    }
    @PostMapping("/subagents/{taskId}/cancel") public SubagentCoordinatorService.SubagentTask cancelSubagentTask(@PathVariable String taskId) {
        return subagents.cancelTask(taskId);
    }
    @GetMapping("/subagents") public List<SubagentCoordinatorService.SubagentTask> listSubagentTasks(@RequestParam(required = false) String parentSessionId) {
        return subagents.listTasks(parentSessionId);
    }
    private static <T> T conflict(java.util.function.Supplier<T> action) {
        try{return action.get();}catch(IllegalStateException denied){throw new ApiFailure(409,"ORCHESTRATION_CONFLICT","The assignment or mandate no longer permits this action.");}
    }
    private static void fields(JsonNode body,String...allowed) {
        if(body==null||!body.isObject()||body.propertyNames().stream().anyMatch(k->!Set.of(allowed).contains(k)))throw new IllegalArgumentException("invalid fields");
    }
    private static String text(JsonNode body,String field,int max) {
        JsonNode n=body.get(field);if(n==null||!n.isString()||n.stringValue().isBlank()||n.stringValue().length()>max)throw new IllegalArgumentException("invalid text");return n.stringValue();
    }
}
