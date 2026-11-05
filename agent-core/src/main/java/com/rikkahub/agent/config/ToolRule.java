package com.rikkahub.agent.config;

import java.nio.file.FileSystems;
import java.util.Objects;
import java.util.regex.Pattern;

/** First matching rule wins. Regex is bounded to a non-repeating safe subset. */
public record ToolRule(String tool, String pathGlob, String commandRegex, String decision) {
    public ToolRule {
        Objects.requireNonNull(tool);Objects.requireNonNull(decision);
        if(!tool.equals("*")&&!tool.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid rule tool");
        if(!java.util.Set.of("ALLOW","ASK","DENY").contains(decision))throw new IllegalArgumentException("invalid rule decision");
        if(pathGlob!=null){if(pathGlob.length()>256)throw new IllegalArgumentException("path rule too long");FileSystems.getDefault().getPathMatcher("glob:"+pathGlob);}
        if(commandRegex!=null){
            if(commandRegex.length()>256||commandRegex.matches(".*[+*{}()].*")||commandRegex.contains("\\1"))throw new IllegalArgumentException("unsafe command regex; use bounded non-repeating patterns");
            Pattern.compile(commandRegex);
        }
    }
}
