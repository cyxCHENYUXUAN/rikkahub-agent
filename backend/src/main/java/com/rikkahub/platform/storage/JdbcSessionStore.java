package com.rikkahub.platform.storage;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import tools.jackson.core.type.TypeReference;
import com.rikkahub.agent.port.SessionStore;
import com.rikkahub.agent.session.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Paginated durable reads; all writes delegate to the single commit service. */
@Repository
public class JdbcSessionStore implements SessionStore {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final SessionCommitService commits;
    public JdbcSessionStore(JdbcClient jdbc, TransactionRunner transactions, EventCodec codec, SessionCommitService commits) {
        this.jdbc=jdbc; this.transactions=transactions; this.codec=codec; this.commits=commits;
    }
    @Override public SessionCommitResult commit(SessionCommit command) { return commits.commit(command); }
    @Override public SessionSnapshot load(SessionId id) { return read(id,Long.MAX_VALUE); }
    @Override public SessionSnapshot loadRange(SessionId id,long afterSeq,int limit) {
        if (afterSeq<0 || limit<1) { throw new IllegalArgumentException("invalid event prefix range"); }
        return read(id,afterSeq>Long.MAX_VALUE-limit?Long.MAX_VALUE:afterSeq+limit);
    }
    private SessionSnapshot read(SessionId id,long end) {
        Loaded loaded=transactions.read(() -> {
            Header row=jdbc.sql("SELECT * FROM agent_sessions WHERE id=:id").param("id",id.value())
                    .query((rs,n)->header(rs,codec)).optional().orElseThrow(()->new NoSuchElementException("session not found: "+id));
            if(row.header().formatVersion()!=SessionFormat.CURRENT) { throw new IllegalArgumentException("unsupported session format"); }
            long stop=Math.min(end,row.lastSeq());
            List<SessionEvent> events=new ArrayList<>();long cursor=0;
            while(cursor<stop) {
                List<SessionEvent> page=jdbc.sql("SELECT * FROM session_events WHERE session_id=:id AND seq>:after AND seq<=:end ORDER BY seq LIMIT 256")
                        .param("id",id.value()).param("after",cursor).param("end",stop).query((rs,n)->codec.readEvent(rs)).list();
                if(page.isEmpty()) { throw new IllegalStateException("session log is not contiguous"); }
                events.addAll(page);cursor=page.get(page.size()-1).seq();
            }
            return new Loaded(row,events);
        });
        return new SessionSnapshot(new Session(loaded.row().header(),loaded.events()),loaded.row().status(),loaded.row().updatedAt());
    }
    @Override public List<SessionSummary> list() {
        return transactions.read(()->jdbc.sql("SELECT id,status,last_seq,updated_at FROM agent_sessions ORDER BY created_at,id")
                .query((rs,n)->new SessionSummary(SessionId.of(rs.getString("id")),SessionStatus.valueOf(rs.getString("status")),
                        rs.getLong("last_seq"),Instant.parse(rs.getString("updated_at")))).list());
    }
    static Header header(ResultSet rs,EventCodec codec) throws SQLException {
        String parent=rs.getString("parent_session_id");
        SessionHeader h=new SessionHeader(SessionId.of(rs.getString("id")),parent==null?null:SessionId.of(parent),
                Path.of(rs.getString("cwd")),rs.getInt("format_version"),rs.getString("origin"),rs.getString("agent_preset"),
                codec.decode(rs.getString("header_metadata_json"),new TypeReference<Map<String,String>>(){}),
                Instant.parse(rs.getString("created_at")));
        return new Header(h,SessionStatus.valueOf(rs.getString("status")),rs.getLong("last_seq"),
                rs.getInt("open_turn")!=0,rs.getInt("current_turn"),rs.getInt("current_step"),
                rs.getLong("turn_start_seq"),rs.getInt("last_attempt"),Instant.parse(rs.getString("updated_at")));
    }
    record Header(SessionHeader header,SessionStatus status,long lastSeq,boolean openTurn,int turn,int step,
                  long turnStartSeq,int attempt,Instant updatedAt) {}
    private record Loaded(Header row,List<SessionEvent> events) {}
}
