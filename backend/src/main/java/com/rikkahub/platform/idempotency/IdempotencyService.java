package com.rikkahub.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.platform.http.ApiFailure;
import com.rikkahub.platform.storage.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The input/configuration write and its replayable response share one root transaction. */
@Service
public final class IdempotencyService {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;
    private final EventCodec codec;
    private final Clock clock;
    public IdempotencyService(JdbcClient jdbc,TransactionRunner transactions,EventCodec codec,Clock clock) {
        this.jdbc=jdbc;this.transactions=transactions;this.codec=codec;this.clock=clock;
    }

    public Result execute(String scope,String key,Object request,Supplier<Reply> action) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new ApiFailure(400,"IDEMPOTENCY_KEY_REQUIRED","A valid Idempotency-Key is required.");
        String hash=hash(canonical(codec.decode(codec.encode(request),JsonNode.class)));
        return transactions.write(()->{
            var old=jdbc.sql("SELECT request_hash,status,response_status,response_json FROM idempotency_keys WHERE scope=:scope AND idempotency_key=:key")
                    .param("scope",scope).param("key",key).query((rs,n)->new Saved(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4))).optional();
            if(old.isPresent()) {
                Saved saved=old.get();
                if(!saved.hash().equals(hash))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT","This key was already used for a different request.");
                if(!saved.status().equals("COMPLETED"))throw new ApiFailure(409,"REQUEST_IN_PROGRESS","This request is already being processed.");
                return new Result(saved.http(),codec.decode(saved.json(),JsonNode.class),true);
            }
            var now=clock.now();
            jdbc.sql("INSERT INTO idempotency_keys(scope,idempotency_key,request_hash,status,created_at,expires_at) VALUES(:scope,:key,:hash,'IN_PROGRESS',:at,:expiry)")
                    .param("scope",scope).param("key",key).param("hash",hash).param("at",EventCodec.timestamp(now))
                    .param("expiry",EventCodec.timestamp(now.plus(Duration.ofDays(1)))).update();
            Reply reply=action.get();String body=codec.encode(reply.body());
            jdbc.sql("UPDATE idempotency_keys SET status='COMPLETED',response_status=:status,response_json=:body WHERE scope=:scope AND idempotency_key=:key")
                    .param("status",reply.status()).param("body",body).param("scope",scope).param("key",key).update();
            return new Result(reply.status(),codec.decode(body,JsonNode.class),false);
        });
    }
    private String hash(JsonNode node) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(codec.encode(node).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            ObjectNode out=JsonNodeFactory.instance.objectNode();TreeSet<String> keys=new TreeSet<>(node.propertyNames());
            for(String key:keys)out.set(key,canonical(node.get(key)));return out;
        }
        if(node.isArray()){ArrayNode out=JsonNodeFactory.instance.arrayNode();node.forEach(v->out.add(canonical(v)));return out;}
        return node.deepCopy();
    }
    public record Reply(int status,Object body) {}
    public record Result(int status,JsonNode body,boolean replayed) {}
    private record Saved(String hash,String status,int http,String json) {}
}
