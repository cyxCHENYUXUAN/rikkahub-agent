package com.rikkahub.features.orchestration.application;

import java.time.Instant;
import java.util.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.platform.storage.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Persistent interval sources use stable tick IDs so a crash between intake and advance is harmless. */
@Service
public final class SourceApplicationService {
    private final JdbcClient jdbc; private final TransactionRunner transactions;
    private final Clock clock; private final IdGenerator ids;
    public SourceApplicationService(JdbcClient jdbc,TransactionRunner transactions,Clock clock,IdGenerator ids) {
        this.jdbc=jdbc;this.transactions=transactions;this.clock=clock;this.ids=ids;
    }
    public record Source(String id,String threadKey,String text,long intervalSeconds,String nextAt,boolean enabled) {}
    public Source create(String thread,String text,long seconds) {
        if(thread==null||thread.isBlank()||thread.length()>256||text==null||text.isBlank()||text.length()>262144||seconds<60||seconds>31536000)
            throw new IllegalArgumentException("invalid interval source");
        Source source=new Source(ids.newId("source"),thread,text,seconds,EventCodec.timestamp(clock.now().plusSeconds(seconds)),true);
        return transactions.write(()->{jdbc.sql("INSERT INTO orchestration_sources(id,thread_key,text,interval_seconds,next_at,enabled,created_at) VALUES(:id,:thread,:text,:interval,:next,1,:now)")
                .param("id",source.id()).param("thread",thread).param("text",text).param("interval",seconds).param("next",source.nextAt()).param("now",EventCodec.timestamp(clock.now())).update();return source;});
    }
    public List<Source> list() { return transactions.read(()->jdbc.sql("SELECT * FROM orchestration_sources ORDER BY created_at,id LIMIT 500").query((r,n)->row(r)).list()); }
    public void enabled(String id,boolean enabled) {
        transactions.write(()->{int count=jdbc.sql("UPDATE orchestration_sources SET enabled=:enabled WHERE id=:id").param("enabled",enabled?1:0).param("id",id).update();
            if(count!=1)throw new NoSuchElementException("source not found");return null;});
    }
    public void intakeDue(OrchestrationApplicationService orchestration) {
        Instant now=clock.now();
        List<Source> due=transactions.read(()->jdbc.sql("SELECT * FROM orchestration_sources WHERE enabled=1 AND next_at<=:now ORDER BY next_at,id LIMIT 32")
                .param("now",EventCodec.timestamp(now)).query((r,n)->row(r)).list());
        for(Source source:due) {
            orchestration.ingest("schedule:"+source.id(),source.nextAt(),source.threadKey(),source.text());
            transactions.write(()->{jdbc.sql("UPDATE orchestration_sources SET next_at=:next WHERE id=:id AND next_at=:old AND enabled=1")
                    .param("next",EventCodec.timestamp(now.plusSeconds(source.intervalSeconds()))).param("id",source.id()).param("old",source.nextAt()).update();return null;});
        }
    }
    private static Source row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Source(r.getString("id"),r.getString("thread_key"),r.getString("text"),r.getLong("interval_seconds"),r.getString("next_at"),r.getInt("enabled")==1);
    }
}
