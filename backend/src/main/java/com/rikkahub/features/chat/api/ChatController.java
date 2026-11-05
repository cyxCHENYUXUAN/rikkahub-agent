package com.rikkahub.features.chat.api;

import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.features.chat.application.ChatApplicationService;
import com.rikkahub.platform.concurrency.SessionCommandQueue;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.http.ApiResponses;
import com.rikkahub.platform.sse.SseHub;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.async.DeferredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatApplicationService chat;
    private final SessionCommandQueue commands;
    private final SseHub sse;
    private final com.rikkahub.features.chat.application.ManualCompactionService compaction;
    public ChatController(ChatApplicationService chat,SessionCommandQueue commands,SseHub sse,
                          com.rikkahub.features.chat.application.ManualCompactionService compaction){
        this.chat=chat;this.commands=commands;this.sse=sse;this.compaction=compaction;
    }
    @PostMapping("/conversations") public ResponseEntity<JsonNode> create(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ApiResponses.response(ApiResponses.await(commands.submit("create-conversation",()->chat.create(key,body))));}
    @GetMapping("/conversations") public List<ChatApplicationService.ConversationView> list(){return chat.list();}
    @GetMapping("/conversations/{id}") public ChatApplicationService.ConversationView get(@PathVariable String id){return chat.get(id);}
    @GetMapping("/conversations/{id}/messages") public List<ChatApplicationService.MessageView> messages(@PathVariable String id){return chat.messages(id);}
    @PostMapping("/conversations/{id}/messages")
    public DeferredResult<ResponseEntity<JsonNode>> send(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body) {
        var accepted=ApiResponses.await(commands.submit(id,()->chat.send(id,key,body)));
        DeferredResult<ResponseEntity<JsonNode>> response=new DeferredResult<>();
        response.onCompletion(()->{
            try { chat.activate(id,accepted.reservation()); }
            catch(RuntimeException error) { log.warn("Accepted input activation failed; retry the original idempotent request."); }
        });
        response.setResult(ApiResponses.response(accepted.response()));
        return response;
    }
    @PatchMapping("/conversations/{id}") public ResponseEntity<JsonNode> patch(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ApiResponses.response(ApiResponses.await(commands.submit(id,()->chat.patch(id,key,body))));}
    @DeleteMapping("/conversations/{id}") public ResponseEntity<JsonNode> archive(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key){return ApiResponses.response(ApiResponses.await(commands.submit(id,()->chat.archive(id,key))));}
    @PostMapping("/conversations/{id}/turns/{turn}/cancel") public ResponseEntity<JsonNode> cancel(@PathVariable String id,@PathVariable int turn,@RequestHeader(value="Idempotency-Key",required=false)String key){return ApiResponses.response(ApiResponses.await(commands.submit(id,()->chat.cancel(id,turn,key))));}
    @GetMapping("/sessions/{id}/events") public ChatApplicationService.EventPage events(@PathVariable String id,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="256")int limit){return chat.events(SessionId.of(id),after,limit);}
    @GetMapping(value="/conversations/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id,@RequestParam(required=false)Long after,
                                               @RequestHeader(value="Last-Event-ID",required=false)String last) {
        SessionId session=SessionId.of(chat.get(id).sessionId());long cursor=after==null?0:after;
        if(last!=null) {
            int colon=last.lastIndexOf(':');
            if(colon<1||!last.substring(0,colon).equals(session.value()))throw new ApiFailure(400,"INVALID_EVENT_CURSOR","The event cursor belongs to another session.");
            try{cursor=Long.parseLong(last.substring(colon+1));}
            catch(IllegalArgumentException error){throw new ApiFailure(400,"INVALID_EVENT_CURSOR","The event cursor is invalid.");}
        }
        // Validate the high-water boundary before committing the streaming response.
        chat.events(session,cursor,1);
        SseEmitter emitter=sse.subscribe(session,cursor);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-cache").header("X-Accel-Buffering","no").body(emitter);
    }

    @PostMapping("/sessions/{id}/compaction")
    public com.rikkahub.features.chat.application.ManualCompactionService.Result compact(@PathVariable String id) {
        return ApiResponses.await(commands.submit("compaction:"+id,
                () -> compaction.compact(SessionId.of(id))), 300);
    }

    @PostMapping("/sessions/{id}/compaction/{seq}/abandon")
    public ResponseEntity<Void> abandon(@PathVariable String id,@PathVariable long seq) {
        if(seq<1)throw new ApiFailure(400,"INVALID_REQUEST","The compaction sequence is invalid.");
        ApiResponses.await(commands.submit("compaction:"+id,()->{compaction.abandon(SessionId.of(id),seq);return null;}),30);
        return ResponseEntity.noContent().build();
    }
}
