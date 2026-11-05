package com.rikkahub.features.mcp.application;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import com.rikkahub.agent.tool.ToolDefinition;
import com.rikkahub.plugins.mcpclient.*;
import com.rikkahub.platform.http.ApiFailure;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Connections are explicit local user actions, never startup side effects. */
@Service
@EnableConfigurationProperties(McpProperties.class)
public final class McpCatalog implements AutoCloseable {
    private final McpProperties properties;private final Environment environment;
    private final Map<String,Connected> connected=new LinkedHashMap<>();
    private boolean closed;
    public McpCatalog(McpProperties properties,Environment environment) {this.properties=properties;this.environment=environment;}
    public record Status(String id,String url,boolean connected,int toolCount) {}
    private record Connected(HttpMcpClient client,List<ToolDefinition> definitions) {}
    public synchronized List<Status> statuses() {
        return properties.servers().stream().map(s->{Connected c=connected.get(s.id());return new Status(s.id(),s.url().toString(),c!=null,c==null?0:c.definitions().size());}).toList();
    }
    public synchronized boolean available() {return !connected.isEmpty()&&!closed;}
    public synchronized List<ToolDefinition> definitions() {return connected.values().stream().flatMap(c->c.definitions().stream()).toList();}
    public Status connect(String id) {
        McpProperties.Server server=properties.servers().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new NoSuchElementException("MCP server not configured"));
        synchronized(this){if(closed)throw new IllegalStateException("MCP catalog closed");if(connected.containsKey(id))return statuses().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}
        HttpMcpClient client=new HttpMcpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(),
                id,server.url(),()->credential(server.credentialRef()),Duration.ofSeconds(30));
        try {
            List<ToolDefinition> tools=McpToolAdapter.definitions(client);
            synchronized(this) {
                if(closed)throw new IllegalStateException("MCP catalog closed");
                Connected previous=connected.putIfAbsent(id,new Connected(client,tools));
                if(previous!=null)client.close();
                return statuses().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
            }
        }catch(RuntimeException failure){client.close();throw new ApiFailure(503,"MCP_UNAVAILABLE","The MCP server could not be connected. Check the configured URL and credential reference.");}
    }
    public synchronized void disconnect(String id) { Connected c=connected.remove(id);if(c!=null)c.client().close(); }
    private String credential(String ref) {
        if(ref==null)return null;String value=environment.getProperty(ref.substring(4));
        if(value==null||value.isBlank())throw new IllegalStateException("MCP credential unavailable");return value;
    }
    @PreDestroy @Override public synchronized void close(){if(closed)return;closed=true;connected.values().forEach(c->c.client().close());connected.clear();}
}
