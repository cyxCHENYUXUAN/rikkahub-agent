package com.rikkahub.agent.orchestration;

import java.time.Instant;
import java.util.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.session.EffectIntent;
import com.rikkahub.agent.orchestration.Orchestration.*;

/** Single command writer. I/O is represented by persisted effects, never executed here. */
public final class Engine {
    private final EngineStore store;
    private final Clock clock;
    private final IdGenerator ids;
    private final WorkerSlotManager slots;
    private final Intake intake=new Intake();
    private final Evals evals=new Evals();
    private final Tickets tickets=new Tickets();
    private final Assignments assignments=new Assignments();
    private final Replies replies=new Replies();
    public Engine(EngineStore store, Clock clock, IdGenerator ids, int workers) {
        this.store=Objects.requireNonNull(store); this.clock=Objects.requireNonNull(clock);
        this.ids=Objects.requireNonNull(ids); this.slots=new WorkerSlotManager(workers);
    }
    public Intake intake() { return intake; }
    public Evals evals() { return evals; }
    public Tickets tickets() { return tickets; }
    public Assignments assignments() { return assignments; }
    public Replies replies() { return replies; }
    public synchronized Snapshot snapshot() { return store.transact(State::snapshot); }
    private synchronized <T> T write(java.util.function.Function<State,T> command) { return store.transact(command); }

    public final class Intake {
        public Eval ingest(String source,String externalId,String threadKey,String text) {
            bounded(source,128); bounded(externalId,256); bounded(threadKey,256); bounded(text,262144);
            return write(s->{
                Trigger duplicate=s.triggers.values().stream().filter(t->t.source().equals(source)&&t.externalId().equals(externalId)).findFirst().orElse(null);
                if(duplicate!=null) return s.evals.values().stream().filter(e->e.triggerId().equals(duplicate.id())).findFirst().orElseThrow();
                Instant now=clock.now();
                Orchestration.Thread thread=s.threads.values().stream().filter(t->t.source().equals(source)&&t.key().equals(threadKey)).findFirst().orElse(null);
                if(thread==null) { thread=new Orchestration.Thread(ids.newId("thread"),source,threadKey,now); s.threads.put(thread.id(),thread); }
                Trigger trigger=new Trigger(ids.newId("trigger"),source,externalId,thread.id(),text,now);
                Eval eval=new Eval(ids.newId("eval"),trigger.id(),thread.id(),null,Decision.OBSERVE,"等待用户决定是否接单",now);
                s.triggers.put(trigger.id(),trigger); s.evals.put(eval.id(),eval); return eval;
            });
        }
    }
    public final class Evals {
        /** Layer one is advisory. Only the host's accept/resolve commands change layer two. */
        public Eval record(String id,Decision recommendation,String reason) {
            Objects.requireNonNull(recommendation); bounded(reason,8192);
            return write(s->{ Eval old=require(s.evals,id);
                Eval next=new Eval(old.id(),old.triggerId(),old.threadId(),recommendation,old.decision(),reason,old.createdAt());
                s.evals.put(id,next); return next; });
        }
        public Eval resolve(String id,Decision decision) {
            if(decision!=Decision.IGNORE&&decision!=Decision.STOP) throw new IllegalArgumentException("use ticket accept to grant a mandate");
            return write(s->{ Eval old=require(s.evals,id);
                if(old.decision()!=Decision.OBSERVE) throw new IllegalStateException("evaluation already resolved");
                Eval next=new Eval(old.id(),old.triggerId(),old.threadId(),old.recommendation(),decision,old.reason(),old.createdAt());
                s.evals.put(id,next); return next; });
        }
    }
    public final class Tickets {
        /** Called only by an authenticated host action; config is frozen before entering this command. */
        public Ticket accept(String evalId,String assistantId,String configJson,Instant expiresAt) {
            bounded(assistantId,128); bounded(configJson,1048576); Objects.requireNonNull(expiresAt);
            if(!clock.now().isBefore(expiresAt)) throw new IllegalArgumentException("mandate must expire in the future");
            return write(s->{
                Eval eval=require(s.evals,evalId);
                Ticket existing=s.tickets.values().stream().filter(t->t.evalId().equals(evalId)).findFirst().orElse(null);
                if(existing!=null) return existing;
                if(eval.decision()!=Decision.OBSERVE) throw new IllegalStateException("evaluation already resolved");
                Instant now=clock.now(); Trigger trigger=require(s.triggers,eval.triggerId());
                Mandate mandate=new Mandate(ids.newId("mandate"),1,assistantId,configJson,expiresAt,false,now);
                Ticket ticket=new Ticket(ids.newId("ticket"),evalId,eval.threadId(),mandate.id(),TicketStatus.OPEN,
                        trigger.text().substring(0,Math.min(120,trigger.text().length())),"等待执行",now,now);
                s.mandates.put(mandate.id(),mandate); s.tickets.put(ticket.id(),ticket);
                s.evals.put(evalId,new Eval(eval.id(),eval.triggerId(),eval.threadId(),eval.recommendation(),Decision.ACCEPT,eval.reason(),eval.createdAt()));
                assign(s,ticket,trigger.text(),true,null); return ticket;
            });
        }
        public Assignment resume(String id,String text,boolean human) {
            return resume(id,text,human,null);
        }
        public Assignment resume(String id,String text,boolean human,String requestId) {
            bounded(text,262144);
            if(requestId!=null) bounded(requestId,128);
            return write(s->{ Ticket ticket=require(s.tickets,id);
                if(requestId!=null) {
                    Assignment previous=s.assignments.values().stream().filter(a->a.ticketId().equals(id)&&requestId.equals(a.requestId())).findFirst().orElse(null);
                    if(previous!=null) {
                        if(!previous.input().equals(text))throw new ConcurrentModificationException("idempotency key has a different input");
                        return previous;
                    }
                }
                authorized(s,ticket);
                if(ticket.status()==TicketStatus.ENDED) throw new IllegalStateException("ticket has ended");
                if(s.assignments.values().stream().anyMatch(a->a.ticketId().equals(id)&&a.status()!=AssignmentStatus.ENDED))
                    throw new IllegalStateException("ticket already has an active assignment");
                s.tickets.put(id,new Ticket(ticket.id(),ticket.evalId(),ticket.threadId(),ticket.mandateId(),TicketStatus.OPEN,ticket.title(),"等待执行",ticket.createdAt(),clock.now()));
                return assign(s,ticket,text,human,requestId);
            });
        }
        public Ticket stop(String id) {
            return write(s->{ Ticket old=require(s.tickets,id); Mandate m=require(s.mandates,old.mandateId());
                s.mandates.put(m.id(),new Mandate(m.id(),m.version(),m.assistantId(),m.configJson(),m.expiresAt(),true,m.createdAt()));
                Ticket ended=new Ticket(old.id(),old.evalId(),old.threadId(),old.mandateId(),TicketStatus.ENDED,old.title(),"授权已撤回",old.createdAt(),clock.now());
                s.tickets.put(id,ended);
                // Running assignments retain their slot until the host confirms actual drain.
                for(Assignment a:List.copyOf(s.assignments.values())) if(a.ticketId().equals(id)&&a.status()==AssignmentStatus.PENDING)
                    s.assignments.put(a.id(),ended(a,Report.DROPPED,"授权已撤回"));
                return ended;
            });
        }
        public Ticket close(String id) {
            return write(s->{ Ticket old=require(s.tickets,id);
                if(s.assignments.values().stream().anyMatch(a->a.ticketId().equals(id)&&a.status()!=AssignmentStatus.ENDED))
                    throw new IllegalStateException("active assignment must drain before closing");
                Ticket ended=new Ticket(old.id(),old.evalId(),old.threadId(),old.mandateId(),TicketStatus.ENDED,old.title(),"已完成",old.createdAt(),clock.now());
                s.tickets.put(id,ended); return ended; });
        }
    }
    public final class Assignments {
        public boolean start(String id) {
            return write(s->{ Assignment a=require(s.assignments,id); Ticket t=require(s.tickets,a.ticketId()); authorized(s,t);
                if(t.status()==TicketStatus.ENDED||a.status()==AssignmentStatus.ENDED) throw new IllegalStateException("assignment ended");
                if(a.status()==AssignmentStatus.RUNNING) return true;
                if(!slots.available(s.assignments.values(),a.human())) return false;
                s.assignments.put(id,new Assignment(a.id(),a.ticketId(),a.mandateId(),a.sessionId(),AssignmentStatus.RUNNING,
                        Report.PROGRESS,a.input(),"正在执行",a.human(),a.createdAt(),clock.now(),a.requestId())); return true; });
        }
        public Assignment report(String id,Report report,String note) {
            Objects.requireNonNull(report); bounded(note,262144);
            return write(s->{ Assignment a=require(s.assignments,id);
                if(a.status()==AssignmentStatus.ENDED) return a;
                boolean progress=report==Report.PROGRESS;
                Assignment next=progress?new Assignment(a.id(),a.ticketId(),a.mandateId(),a.sessionId(),a.status(),report,a.input(),note,a.human(),a.createdAt(),clock.now(),a.requestId()):ended(a,report,note);
                s.assignments.put(id,next); Ticket t=require(s.tickets,a.ticketId());
                if(t.status()!=TicketStatus.ENDED) {
                    TicketStatus status=progress?TicketStatus.OPEN:report==Report.READY?TicketStatus.READY:TicketStatus.WAITING;
                    s.tickets.put(t.id(),new Ticket(t.id(),t.evalId(),t.threadId(),t.mandateId(),status,t.title(),note,t.createdAt(),clock.now()));
                    if(!progress) { Reply reply=new Reply(ids.newId("reply"),t.id(),note,clock.now()); s.replies.put(reply.id(),reply); }
                }
                return next;
            });
        }
        public List<String> recover() {
            return write(s->{ List<String> recovered=new ArrayList<>();
                var running=s.assignments.values().stream().filter(a->a.status()==AssignmentStatus.RUNNING)
                        .sorted(Comparator.comparing((Assignment a)->require(s.tickets,a.ticketId()).createdAt()).thenComparing(Assignment::createdAt)).toList();
                for(Assignment a:running) {
                    String note="执行已中断；请先检查工作目录和外部状态，再决定是否继续。不会自动重放模型或工具。";
                    s.assignments.put(a.id(),ended(a,Report.BLOCKED,note)); Ticket t=require(s.tickets,a.ticketId());
                    if(t.status()!=TicketStatus.ENDED) s.tickets.put(t.id(),new Ticket(t.id(),t.evalId(),t.threadId(),t.mandateId(),TicketStatus.WAITING,t.title(),note,t.createdAt(),clock.now()));
                    recovered.add(t.id());
                }
                return List.copyOf(recovered);
            });
        }
    }
    public final class Replies {
        public List<Reply> list(String ticketId) { return snapshot().replies().values().stream().filter(r->r.ticketId().equals(ticketId)).toList(); }
    }
    private Assignment assign(State s,Ticket ticket,String text,boolean human,String requestId) {
        String id=ids.newId("assignment"); Instant now=clock.now();
        Assignment a=new Assignment(id,ticket.id(),ticket.mandateId(),"worker-"+ticket.id(),AssignmentStatus.PENDING,Report.PROGRESS,
                text,ticket.note(),human,now,now,requestId);
        s.assignments.put(id,a); s.effect(new EffectIntent("worker-start-"+id,"worker.start",id,now)); return a;
    }
    private Assignment ended(Assignment a,Report report,String note) {
        return new Assignment(a.id(),a.ticketId(),a.mandateId(),a.sessionId(),AssignmentStatus.ENDED,report,a.input(),note,a.human(),a.createdAt(),clock.now(),a.requestId());
    }
    private void authorized(State s,Ticket ticket) {
        if(!require(s.mandates,ticket.mandateId()).valid(clock.now())) throw new IllegalStateException("mandate is revoked or expired");
    }
    private static <T> T require(Map<String,T> values,String id) {
        T value=values.get(id); if(value==null) throw new NoSuchElementException("orchestration resource not found"); return value;
    }
    private static void bounded(String value,int limit) {
        if(value==null||value.isBlank()||value.length()>limit) throw new IllegalArgumentException("text is empty or exceeds its limit");
    }
}
