package com.rikkahub.plugins.mcpclient;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.port.*;
import tools.jackson.databind.*;

/** MCP 2025-11-25 Streamable HTTP, including JSON/SSE responses and explicit cancellation. No call replay. */
public final class HttpMcpClient implements McpPort {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_BYTES=4*1024*1024;
    private final HttpClient http; private final String server; private final URI endpoint;
    private final Supplier<String> credential; private final Duration timeout;
    private final AtomicLong ids=new AtomicLong();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mcp-deadline");t.setDaemon(true);return t;});
    private final Set<InputStream> bodies=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile String session;
    private volatile String version="2025-11-25";
    private volatile List<McpToolDescriptor> tools;
    public HttpMcpClient(HttpClient http,String server,URI endpoint,Supplier<String> credential,Duration timeout) {
        McpNames.tool(server,"probe");
        if(endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getFragment()!=null||!Set.of("http","https").contains(endpoint.getScheme()))throw new IllegalArgumentException("invalid MCP endpoint");
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("invalid MCP timeout");
        this.http=Objects.requireNonNull(http);this.server=server;this.endpoint=endpoint;this.credential=Objects.requireNonNull(credential);this.timeout=timeout;
    }
    @Override public synchronized List<McpToolDescriptor> tools() {
        ensureOpen(); if(tools!=null)return tools;
        try {
            JsonNode init=request("initialize",Map.of("protocolVersion",version,"capabilities",Map.of(),"clientInfo",Map.of("name","RikkaHub","version","1.0")),CancellationToken.none());
            String negotiated=init.path("protocolVersion").asString();
            if(!Set.of("2025-03-26","2025-06-18","2025-11-25").contains(negotiated))throw new IllegalStateException("mcp_unsupported_protocol_version");
            version=negotiated;
            notify("notifications/initialized",Map.of());
            if(!init.path("capabilities").has("tools")){tools=List.of();return tools;}
            List<McpToolDescriptor> descriptors=new ArrayList<>();Set<String> names=new HashSet<>(),cursors=new HashSet<>();String cursor=null;
            do {
                JsonNode page=request("tools/list",cursor==null?Map.of():Map.of("cursor",cursor),CancellationToken.none());
                if(!page.path("tools").isArray())throw new IllegalStateException("mcp_invalid_tool_list");
                for(JsonNode tool:page.path("tools")) {
                    String name=tool.path("name").asString();McpNames.tool(server,name);
                    JsonNode schema=tool.get("inputSchema");
                    if(schema==null||!schema.isObject()||!"object".equals(schema.path("type").asString())||!names.add(name)||descriptors.size()>=256)throw new IllegalStateException("mcp_invalid_tool_schema");
                    descriptors.add(new McpToolDescriptor(server,name,tool.path("description").asString(""),JSON.writeValueAsString(schema)));
                }
                cursor=page.hasNonNull("nextCursor")?page.path("nextCursor").asString():null;
                if(cursor!=null&&(cursor.isBlank()||!cursors.add(cursor)||cursors.size()>32))throw new IllegalStateException("mcp_invalid_pagination");
            }while(cursor!=null);
            tools=List.copyOf(descriptors);return tools;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("mcp_interrupted");}
        catch(Exception failure){throw new IllegalStateException("mcp_connection_failed",failure);}
    }
    @Override public String call(String tool,String argumentsJson) throws Exception {return call(tool,argumentsJson,CancellationToken.none());}
    @Override public String call(String tool,String argumentsJson,CancellationToken signal) throws Exception {
        ensureOpen();signal.throwIfCancelled();
        if(tools().stream().noneMatch(t->t.tool().equals(tool)))throw new IllegalArgumentException("mcp_tool_not_found");
        JsonNode args=JSON.readTree(argumentsJson);if(!args.isObject())throw new IllegalArgumentException("mcp_arguments_must_be_object");
        return JSON.writeValueAsString(request("tools/call",Map.of("name",tool,"arguments",args),signal));
    }
    private JsonNode request(String method,Object params,CancellationToken signal) throws Exception {
        ensureOpen();long id=ids.incrementAndGet();
        var future=http.sendAsync(builder().POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofInputStream());
        AtomicReference<InputStream> body=new AtomicReference<>();AtomicBoolean expired=new AtomicBoolean();
        Runnable abort=()->{expired.set(true);future.cancel(true);close(body.get());};
        AutoCloseable cancellation=signal.onCancel(()->{abort.run();cancelNotification(id);});
        ScheduledFuture<?> deadline=deadlines.schedule(abort,timeout.toMillis(),TimeUnit.MILLISECONDS);
        try {
            HttpResponse<InputStream> response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            body.set(response.body());bodies.add(response.body());
            if(expired.get()||closed||signal.isCancelled()){close(response.body());throw new IllegalStateException("mcp_cancelled_or_timed_out");}
            if(response.statusCode()!=200)throw new IllegalStateException("mcp_http_"+response.statusCode());
            if(method.equals("initialize")) {
                String assigned=response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if(assigned!=null&&!assigned.matches("[\\x21-\\x7e]{1,1024}"))throw new IllegalStateException("mcp_invalid_session");
                session=assigned;
            }
            String type=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            JsonNode message;
            if(type.startsWith("application/json")) message=JSON.readTree(readLimited(response.body()));
            else if(type.startsWith("text/event-stream")) message=readSse(response.body(),id);
            else throw new IllegalStateException("mcp_invalid_content_type");
            if(!"2.0".equals(message.path("jsonrpc").asString())||!message.path("id").isIntegralNumber()||message.path("id").longValue()!=id)
                throw new IllegalStateException("mcp_response_id_mismatch");
            if(message.has("error"))throw new IllegalStateException("mcp_rpc_error");
            if(!message.has("result"))throw new IllegalStateException("mcp_missing_result");
            return message.get("result");
        } finally {
            deadline.cancel(false);cancellation.close();InputStream stream=body.get();if(stream!=null)bodies.remove(stream);close(stream);
            if(!future.isDone())future.cancel(true);
        }
    }
    private HttpRequest.Builder builder() {
        HttpRequest.Builder builder=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json")
                .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",version);
        if(session!=null)builder.header("Mcp-Session-Id",session);
        String secret=credential.get();if(secret!=null&&!secret.isBlank())builder.header("Authorization","Bearer "+secret);
        return builder;
    }
    private void notify(String method,Object params) throws Exception {
        HttpResponse<Void> response=http.send(builder().POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","method",method,"params",params)))).build(),HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=202)throw new IllegalStateException("mcp_notification_rejected");
    }
    private void cancelNotification(long id) {
        try {http.sendAsync(builder().POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc","2.0","method","notifications/cancelled","params",Map.of("requestId",id,"reason","client_cancelled"))))).build(),HttpResponse.BodyHandlers.discarding());}
        catch(RuntimeException ignored){/* best effort cancellation does not replay the call */}
    }
    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("mcp_response_too_large");return bytes;
    }
    private static JsonNode readSse(InputStream input,long id) throws IOException {
        StringBuilder data=new StringBuilder();int bytes=0;
        // Count before allocating a complete line; an untrusted server can omit newlines.
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        for(int value;(value=input.read())!=-1;) {
            if(++bytes>MAX_BYTES)throw new IOException("mcp_response_too_large");
            if(value!='\n'){line.write(value);continue;}
            String text=line.toString(StandardCharsets.UTF_8);line.reset();if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
            if(text.isEmpty()) {
                if(!data.isEmpty()) {
                    JsonNode message=JSON.readTree(data.toString());data.setLength(0);
                    if(message.path("id").isIntegralNumber()&&message.path("id").longValue()==id&&(message.has("result")||message.has("error")))return message;
                    if(message.has("method")&&message.has("id"))throw new IOException("mcp_server_requests_not_supported");
                }
            } else if(text.startsWith("data:")) {
                String content=text.substring(5);if(content.startsWith(" "))content=content.substring(1);
                if(!content.isEmpty()){if(!data.isEmpty())data.append('\n');data.append(content);}
            }
        }
        throw new IOException("mcp_truncated_stream");
    }
    @Override public void close() {
        if(closed)return;closed=true;bodies.forEach(HttpMcpClient::close);bodies.clear();deadlines.shutdownNow();
        if(session!=null)try{http.sendAsync(builder().DELETE().build(),HttpResponse.BodyHandlers.discarding());}catch(RuntimeException ignored){}
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("mcp_connection_closed");}
    private static void close(InputStream body){if(body!=null)try{body.close();}catch(IOException ignored){}}
}
