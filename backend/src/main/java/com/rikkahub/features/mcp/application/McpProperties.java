package com.rikkahub.features.mcp.application;

import java.util.*;
import java.net.URI;
import com.rikkahub.plugins.mcpclient.McpNames;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rikkahub.mcp")
public record McpProperties(List<Server> servers) {
    public McpProperties {
        servers=servers==null?List.of():List.copyOf(servers);
        Set<String> ids=new HashSet<>();
        for(Server server:servers)if(!ids.add(server.id()))throw new IllegalArgumentException("duplicate MCP server id");
    }
    public record Server(String id,URI url,String credentialRef) {
        public Server {
            McpNames.tool(id,"probe");Objects.requireNonNull(url);
            if(credentialRef!=null&&!credentialRef.matches("env:[A-Z_][A-Z0-9_]{0,127}"))throw new IllegalArgumentException("invalid MCP credential reference");
        }
    }
}
