package com.rikkahub.platform.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.config.AgentConfigSnapshot;
import com.rikkahub.agent.message.TokenUsage;
import com.rikkahub.agent.port.ClaimedInput;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.session.*;
import com.rikkahub.agent.session.event.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** One short transaction owns log progress, projections, configuration, usage and consumption. */
@Service
public final class SessionCommitService {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final JdbcInboxStore inbox;
    private final List<SessionProjection> projections;
    private final Clock clock;
    private final IdGenerator ids;
    private final DurableWorkQueue effects;

    public SessionCommitService(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec,
                                 JdbcInboxStore inbox, List<SessionProjection> projections, Clock clock, IdGenerator ids,
                                 DurableWorkQueue effects) {
        this.jdbc=jdbc;this.transactions=transactions;this.codec=codec;this.inbox=inbox;
        this.projections=List.copyOf(projections);this.clock=clock;this.ids=ids;
        this.effects=effects;
    }

    public SessionCommitResult commit(SessionCommit command) {
        Objects.requireNonNull(command,"command");
        List<Encoded> encoded=prepare(command);
        return transactions.write(()->commitTransaction(command,encoded));
    }

    /**
     * Application composition only: create the product row and its new session
     * in the same root transaction. Notifications must wait for that root to return.
     */
    public SessionCommitResult commitInCurrentTransaction(SessionCommit command) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a root application transaction is required");
        }
        Objects.requireNonNull(command,"command");
        return commitTransaction(command,prepare(command));
    }

    private List<Encoded> prepare(SessionCommit command) {
        SessionHeader validation=command.header()==null
                ?SessionHeader.newSession(command.sessionId(),Path.of("."),Instant.EPOCH):command.header();
        if(validation.formatVersion()!=SessionFormat.CURRENT) {throw new IllegalArgumentException("unsupported session format");}
        List<NewSessionEvent> data=command.fixedEvents().isEmpty()?command.events():command.fixedEvents().stream()
                .map(e->new NewSessionEvent(e.data(),e.surface(),e.ignorable())).toList();
        List<Encoded> rows=new ArrayList<>(data.size());
        for(NewSessionEvent event:data) {
            new Session(validation,List.of(new SessionEvent(1,Instant.EPOCH,event.data(),event.surface(),event.ignorable())));
            AgentConfigSnapshot config=null;
            if(event.data() instanceof RequestHeaderEvent request) {
                JsonNode snapshot=codec.decode(request.requestJson(),JsonNode.class).path("metadata").get("configSnapshot");
                if(snapshot!=null&&!snapshot.isNull()) {config=codec.decode(codec.encode(snapshot),AgentConfigSnapshot.class);}
            }
            rows.add(new Encoded(codec.eventType(event.data()),codec.encode(event.data()),
                    event.surface()==null?null:codec.encode(event.surface().op()),
                    event.surface()==null?null:codec.encode(event.surface().sourceEventSeqs()),config));
        }
        for(UsageRecord record:command.usage()) {
            if(!record.sessionId().equals(command.sessionId())) {throw new IllegalArgumentException("usage belongs to another session");}
        }
        return List.copyOf(rows);
    }

    private SessionCommitResult commitTransaction(SessionCommit command,List<Encoded> encoded) {
        JdbcSessionStore.Header previous=find(command.sessionId());
        boolean creating=previous==null;
        if(creating&&command.header()==null) {throw new NoSuchElementException("new session requires a header");}
        if(!creating&&command.header()!=null) {throw new IllegalStateException("session already exists");}
        Instant now=clock.now();
        if(creating) {
            SessionHeader h=command.header();
            previous=new JdbcSessionStore.Header(h,SessionStatus.CREATED,0,false,0,0,0,0,now);
        }
        if(previous.header().formatVersion()!=SessionFormat.CURRENT) {throw new IllegalArgumentException("unsupported session format");}
        if(command.expectedSeq()!=previous.lastSeq()) {throw new ConcurrentModificationException("session sequence conflict");}
        SessionStatus next=previous.status();
        if(command.status()!=null) {
            if(command.status().expected()!=previous.status()) {throw new ConcurrentModificationException("session status conflict");}
            next=command.status().next();
        }
        if(!command.fixedEvents().isEmpty()) {validateFork(command);}
        List<SessionEvent> events=new ArrayList<>(command.fixedEvents());
        long last=previous.lastSeq();
        for(NewSessionEvent event:command.events()) {
            last=Math.incrementExact(last);
            events.add(new SessionEvent(last,now,event.data(),event.surface(),event.ignorable()));
        }
        if(!command.fixedEvents().isEmpty()) {last=events.get(events.size()-1).seq();}
        Progress progress=progress(previous,events);
        if(command.inboxConsumption()!=null) {
            ClaimedInput claimed=inbox.currentClaim(command.sessionId(),command.inboxConsumption(),now);
            validateConsumption(claimed,events,previous.openTurn(),progress.open(),next);
        }
        if(creating) {insertHeader(previous.header(),command.fixedEvents().size(),now);}
        AgentConfigSnapshot latest=null;
        for(Encoded row:encoded) {if(row.config()!=null)latest=row.config();}
        int changed=jdbc.sql("""
                UPDATE agent_sessions SET last_seq=:last,status=:next,updated_at=:at,
                ended_at=CASE WHEN :next='ENDED' THEN :at ELSE NULL END,
                open_turn=:open,current_turn=:turn,current_step=:step,turn_start_seq=:start,last_attempt=:attempt,
                config_snapshot_json=CASE WHEN :hasConfig=1 THEN :config ELSE config_snapshot_json END,
                config_version=CASE WHEN :hasConfig=1 THEN :version ELSE config_version END
                WHERE id=:id AND last_seq=:expected AND status=:old
                """).param("last",last).param("next",next.name()).param("at",EventCodec.timestamp(now))
                .param("open",progress.open()?1:0).param("turn",progress.turn()).param("step",progress.step())
                .param("start",progress.start()).param("attempt",progress.attempt())
                .param("hasConfig",latest==null?0:1).param("config",latest==null?null:codec.encode(latest))
                .param("version",latest==null?null:latest.version()).param("id",command.sessionId().value())
                .param("expected",previous.lastSeq()).param("old",previous.status().name()).update();
        if(changed!=1) {throw new ConcurrentModificationException("session changed during commit");}
        for(int i=0;i<events.size();i++) {insertEvent(command.sessionId(),events.get(i),encoded.get(i));}
        ProjectionContext context=new ProjectionContext(jdbc,previous.header(),previous.lastSeq(),last,previous.turnStartSeq(),now);
        for(SessionProjection projection:projections) {projection.apply(command.sessionId(),List.copyOf(events),context);}
        writeUsage(command,events,previous,last);
        for(EffectIntent effect:command.effects()) effects.enqueueInCurrentTransaction("effects",command.sessionId().value(),effect);
        if(command.inboxConsumption()!=null) {inbox.completeInCommit(command.sessionId(),command.inboxConsumption(),now);}
        String conversation=previous.header().metadata().get("conversationId");
        long metadata=conversation==null?0:jdbc.sql("SELECT metadata_version FROM conversations WHERE id=:id")
                .param("id",conversation).query(Long.class).single();
        return new SessionCommitResult(command.sessionId(),last,last,metadata);
    }

    private JdbcSessionStore.Header find(SessionId id) {
        return jdbc.sql("SELECT * FROM agent_sessions WHERE id=:id").param("id",id.value())
                .query((rs,n)->JdbcSessionStore.header(rs,codec)).optional().orElse(null);
    }
    private void insertHeader(SessionHeader h,int inherited,Instant now) {
        jdbc.sql("""
                INSERT INTO agent_sessions(id,conversation_id,parent_session_id,format_version,cwd,
                header_metadata_json,seeded,inherited_event_count,origin,agent_preset,status,created_at,updated_at)
                VALUES(:id,:conversation,:parent,:format,:cwd,:metadata,:seeded,:count,:origin,:preset,'CREATED',:created,:at)
                """).param("id",h.id().value()).param("conversation",h.metadata().get("conversationId"))
                .param("parent",h.parentSessionId()==null?null:h.parentSessionId().value()).param("format",h.formatVersion())
                .param("cwd",h.cwd().toString()).param("metadata",codec.encode(h.metadata()))
                .param("seeded",h.parentSessionId()==null?0:1).param("count",inherited).param("origin",h.origin())
                .param("preset",h.agentPreset()).param("created",EventCodec.timestamp(h.createdAt()))
                .param("at",EventCodec.timestamp(now)).update();
    }
    private void insertEvent(SessionId session,SessionEvent event,Encoded encoded) {
        SurfaceOp.SurfaceReplace replace=event.surface()!=null&&event.surface().op() instanceof SurfaceOp.SurfaceReplace r?r:null;
        jdbc.sql("""
                INSERT INTO session_events(session_id,seq,event_time,event_type,data_json,surface_op_json,
                surface_start_seq,surface_end_seq,source_event_seqs_json,ignorable)
                VALUES(:id,:seq,:at,:type,:data,:surface,:start,:end,:sources,:ignorable)
                """).param("id",session.value()).param("seq",event.seq()).param("at",EventCodec.timestamp(event.time()))
                .param("type",encoded.type()).param("data",encoded.data()).param("surface",encoded.surface())
                .param("start",replace==null?null:replace.startSeq()).param("end",replace==null?null:replace.endSeq())
                .param("sources",encoded.sources()).param("ignorable",event.ignorable()?1:0).update();
    }
    private void validateFork(SessionCommit command) {
        JdbcSessionStore.Header parent=find(command.header().parentSessionId());
        if(parent==null) {throw new NoSuchElementException("fork parent not found");}
        if(parent.status()!=SessionStatus.IDLE||parent.openTurn()) {throw new IllegalStateException("fork parent must be idle");}
        List<SessionEvent> fixed=command.fixedEvents();
        if(fixed.size()>parent.lastSeq()||!(fixed.get(fixed.size()-1).data() instanceof TurnEnd)) {
            throw new IllegalArgumentException("fork requires a closed-turn boundary");
        }
        int index=0;
        while(index<fixed.size()) {
            List<SessionEvent> page=jdbc.sql("SELECT * FROM session_events WHERE session_id=:id AND seq>:after AND seq<=:end ORDER BY seq LIMIT 256")
                    .param("id",parent.header().id().value()).param("after",index).param("end",fixed.size())
                    .query((rs,n)->codec.readEvent(rs)).list();
            if(page.isEmpty()) {throw new IllegalArgumentException("fork prefix is incomplete");}
            for(SessionEvent event:page) {
                if(!event.equals(fixed.get(index++))) {throw new IllegalArgumentException("fork prefix differs from parent");}
            }
        }
    }
    private static void validateConsumption(ClaimedInput input,List<SessionEvent> events,boolean oldOpen,boolean finalOpen,SessionStatus next) {
        boolean open=oldOpen;boolean newTurn=false;int matches=0;
        for(SessionEvent event:events) {
            if(event.data() instanceof TurnStart) {open=true;newTurn=true;}
            if(event.data() instanceof TurnEnd) {open=false;}
            if(event.data() instanceof UserMessageEvent user&&user.inputId().equals(input.id().value())) {
                matches++;
                if(!open||!user.content().equals(input.item().content())||event.ignorable()||event.surface()==null
                        ||!(event.surface().op() instanceof SurfaceOp.SurfaceAppend)
                        ||(input.target()==InboxTarget.NEXT_TURN&&!newTurn)) {
                    throw new IllegalArgumentException("consumed input requires its visible message after turn start");
                }
            }
        }
        if(matches!=1||!finalOpen||next!=SessionStatus.RUNNING) {throw new IllegalArgumentException("invalid input consumption batch");}
    }
    private static Progress progress(JdbcSessionStore.Header previous,List<SessionEvent> events) {
        boolean open=previous.openTurn();int turn=previous.turn(),step=previous.step(),attempt=previous.attempt();long start=previous.turnStartSeq();
        for(SessionEvent event:events) {
            if(event.data() instanceof TurnStart t) {open=true;turn=t.turn();step=0;attempt=0;start=event.seq();}
            if(event.data() instanceof TurnEnd) {open=false;}
            if(event.data() instanceof StepStart s) {turn=s.turn();step=s.step();attempt=0;}
            if(event.data() instanceof AssistantAttemptEvent a) {attempt=a.attempt();}
        }
        return new Progress(open,turn,step,start,attempt);
    }
    private void writeUsage(SessionCommit command,List<SessionEvent> events,JdbcSessionStore.Header previous,long last) {
        if(command.usage().isEmpty())return;
        if(last==0) {throw new IllegalArgumentException("usage requires an event anchor");}
        List<Billable> anchors=new ArrayList<>();int turn=previous.turn(),step=previous.step(),attempt=previous.attempt();
        for(SessionEvent event:events) {
            if(event.data() instanceof TurnStart t) {turn=t.turn();step=0;attempt=0;}
            if(event.data() instanceof StepStart s) {turn=s.turn();step=s.step();attempt=0;}
            if(event.data() instanceof AssistantAttemptEvent a) {attempt=a.attempt();anchors.add(new Billable(event.seq(),turn,step,attempt,a.usage(),null,null));}
            if(event.data() instanceof AssistantMessageEvent a) {anchors.add(new Billable(event.seq(),turn,step,attempt+1,a.usage(),a.provider(),a.model()));}
            if(event.data() instanceof CompactionSummaryEvent c) {anchors.add(new Billable(event.seq(),turn,step,1,c.usage(),c.provider(),c.model()));}
        }
        boolean hasBillableEvents=!anchors.isEmpty();
        for(UsageRecord usage:command.usage()) {
            Billable anchor=null;
            for(int i=0;i<anchors.size();i++) {
                Billable candidate=anchors.get(i);
                if(candidate.usage().equals(usage.usage())&&(candidate.provider()==null
                        ||(candidate.provider().equals(usage.provider())&&candidate.model().equals(usage.model())))) {
                    anchor=anchors.remove(i);break;
                }
            }
            if(anchor==null&&(hasBillableEvents||"turn".equals(usage.purpose()))) {throw new IllegalArgumentException("usage requires a matching billable event and provider/model");}
            if(anchor==null)anchor=new Billable(last,previous.turn(),previous.step(),1,usage.usage(),null,null);
            int ordinal=jdbc.sql("SELECT coalesce(max(record_index),0)+1 FROM usage_records WHERE session_id=:id AND seq=:seq")
                    .param("id",command.sessionId().value()).param("seq",anchor.seq()).query(Integer.class).single();
            TokenUsage tokens=usage.usage();
            jdbc.sql("""
                    INSERT INTO usage_records(id,session_id,seq,record_index,turn,step,attempt,provider,model,purpose,
                    input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,created_at)
                    VALUES(:id,:session,:seq,:ordinal,:turn,:step,:attempt,:provider,:model,:purpose,:input,:output,:read,:write,:at)
                    """).param("id",ids.newId("usage")).param("session",command.sessionId().value()).param("seq",anchor.seq())
                    .param("ordinal",ordinal).param("turn",anchor.turn()).param("step",anchor.step()).param("attempt",anchor.attempt())
                    .param("provider",usage.provider()).param("model",usage.model()).param("purpose",usage.purpose())
                    .param("input",tokens.inputTokens()).param("output",tokens.outputTokens())
                    .param("read",tokens.cacheReadTokens()==null?0:tokens.cacheReadTokens())
                    .param("write",tokens.cacheWriteTokens()==null?0:tokens.cacheWriteTokens())
                    .param("at",EventCodec.timestamp(usage.at())).update();
        }
    }
    private record Encoded(String type,String data,String surface,String sources,AgentConfigSnapshot config) {}
    private record Progress(boolean open,int turn,int step,long start,int attempt) {}
    private record Billable(long seq,int turn,int step,int attempt,TokenUsage usage,String provider,String model) {}
}
