package com.rikkahub.agent.tool;

import java.nio.file.Path;
import java.util.*;

/** Registrations are capabilities owned by an exact session/agent/workspace scope. */
public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<Key,Map<String,ToolDefinition>> definitions=new LinkedHashMap<>();
    @Override public synchronized List<ToolSchema> schemas(ToolScope scope){
        return definitions.getOrDefault(key(scope),Map.of()).entrySet().stream()
                .filter(e->enabled(e.getKey(),scope)).map(e->e.getValue().schema()).toList();
    }
    @Override public synchronized Optional<ToolDefinition> find(String name,ToolScope scope){
        return enabled(name,scope)?Optional.ofNullable(definitions.getOrDefault(key(scope),Map.of()).get(name)):Optional.empty();
    }
    @Override public synchronized AutoCloseable register(ToolDefinition tool,ToolScope scope){
        Objects.requireNonNull(tool);Key key=key(scope);String name=tool.schema().name();
        definitions.computeIfAbsent(key,k->new LinkedHashMap<>()).put(name,tool);
        return ()->{synchronized(this){var owned=definitions.get(key);if(owned!=null){owned.remove(name,tool);if(owned.isEmpty())definitions.remove(key);}}};
    }
    private static Key key(ToolScope scope){return new Key(scope.sessionId(),scope.agentId(),scope.cwd().toAbsolutePath().normalize());}
    private static boolean enabled(String name,ToolScope scope){return scope.enabledTools().isEmpty()||scope.enabledTools().contains(name);}
    private record Key(String session,String agent,Path cwd){}
}
