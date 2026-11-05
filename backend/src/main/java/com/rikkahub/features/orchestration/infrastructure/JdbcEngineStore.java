package com.rikkahub.features.orchestration.infrastructure;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import com.rikkahub.agent.orchestration.*;
import com.rikkahub.agent.orchestration.Orchestration.*;
import com.rikkahub.agent.session.EffectIntent;
import com.rikkahub.platform.storage.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Independent aggregate rows; only changed records are written by an Engine command. */
@Repository
public class JdbcEngineStore implements EngineStore {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final DurableWorkQueue work;
    public JdbcEngineStore(JdbcClient jdbc,TransactionRunner transactions,EventCodec codec,DurableWorkQueue work) {
        this.jdbc=jdbc; this.transactions=transactions; this.codec=codec; this.work=work;
    }
    @Override public <T> T transact(Function<State,T> command) {
        return transactions.write(()->{
            var before=load(); State state=new State(before); T result=command.apply(state); var after=state.snapshot();
            save("trigger",before.triggers(),after.triggers(),Trigger::createdAt);
            save("thread",before.threads(),after.threads(),Orchestration.Thread::createdAt);
            save("eval",before.evals(),after.evals(),Eval::createdAt);
            save("mandate",before.mandates(),after.mandates(),Mandate::createdAt);
            save("ticket",before.tickets(),after.tickets(),Ticket::createdAt);
            save("assignment",before.assignments(),after.assignments(),Assignment::createdAt);
            save("reply",before.replies(),after.replies(),Reply::createdAt);
            for(EffectIntent effect:state.effects()) work.enqueueInCurrentTransaction("effects",null,effect);
            for(Reply reply:after.replies().values()) if(!before.replies().containsKey(reply.id()))
                work.enqueueInCurrentTransaction("outbox",null,new EffectIntent(reply.id(),"local.reply",codec.encode(reply),reply.createdAt()));
            return result;
        });
    }
    private Snapshot load() {
        Map<String,Trigger> triggers=new LinkedHashMap<>(); Map<String,Orchestration.Thread> threads=new LinkedHashMap<>();
        Map<String,Eval> evals=new LinkedHashMap<>(); Map<String,Mandate> mandates=new LinkedHashMap<>();
        Map<String,Ticket> tickets=new LinkedHashMap<>(); Map<String,Assignment> assignments=new LinkedHashMap<>(); Map<String,Reply> replies=new LinkedHashMap<>();
        jdbc.sql("SELECT kind,id,data_json FROM orchestration_entities ORDER BY created_at,rowid").query((r,n)->{
            String id=r.getString(2),json=r.getString(3);
            switch(r.getString(1)) {
                case "trigger"->triggers.put(id,codec.decode(json,Trigger.class));
                case "thread"->threads.put(id,codec.decode(json,Orchestration.Thread.class));
                case "eval"->evals.put(id,codec.decode(json,Eval.class));
                case "mandate"->mandates.put(id,codec.decode(json,Mandate.class));
                case "ticket"->tickets.put(id,codec.decode(json,Ticket.class));
                case "assignment"->assignments.put(id,codec.decode(json,Assignment.class));
                case "reply"->replies.put(id,codec.decode(json,Reply.class));
                default->throw new IllegalStateException("unknown orchestration entity");
            }
            return id;
        }).list();
        return new Snapshot(triggers,threads,evals,mandates,tickets,assignments,replies);
    }
    private <T> void save(String kind,Map<String,T> before,Map<String,T> after,Function<T,Instant> created) {
        for(var entry:after.entrySet()) if(!Objects.equals(before.get(entry.getKey()),entry.getValue()))
            jdbc.sql("INSERT INTO orchestration_entities(kind,id,data_json,created_at) VALUES(:kind,:id,:json,:at) ON CONFLICT(kind,id) DO UPDATE SET data_json=excluded.data_json")
                    .param("kind",kind).param("id",entry.getKey()).param("json",codec.encode(entry.getValue()))
                    .param("at",EventCodec.timestamp(created.apply(entry.getValue()))).update();
    }
}
