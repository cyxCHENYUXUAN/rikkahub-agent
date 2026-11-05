package com.rikkahub.agent.config;

import java.util.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import com.rikkahub.agent.approval.ApprovalPolicy;
import com.rikkahub.agent.tool.ToolDecision;

public record ToolPolicy(Set<String> allowedTools, boolean allowAll, ApprovalPolicy defaultApproval,
                         PermissionPreset preset, List<ToolRule> rules) {
    public ToolPolicy {
        allowedTools=Set.copyOf(Objects.requireNonNull(allowedTools));Objects.requireNonNull(defaultApproval);
        for(String tool:allowedTools)if(tool.isBlank())throw new IllegalArgumentException("blank tool name");
        preset=preset==null?PermissionPreset.WORKSPACE_WRITE:preset;
        rules=rules==null?List.of():List.copyOf(rules);
        if(rules.size()>64)throw new IllegalArgumentException("too many tool rules");
    }
    public ToolPolicy(Set<String> allowedTools,boolean allowAll,ApprovalPolicy defaultApproval){this(allowedTools,allowAll,defaultApproval,PermissionPreset.WORKSPACE_WRITE,List.of());}
    public static ToolPolicy allTools(){return new ToolPolicy(Set.of(),true,ApprovalPolicy.ASK);}
    public ToolDecision decide(String tool,JsonNode arguments){
        if(!allowAll&&!allowedTools.contains(tool))return new ToolDecision.Deny("tool not enabled");
        boolean readOnlyTool=Set.of("read","glob","grep","worker_report").contains(tool);
        // READ_ONLY is a hard ceiling: an explicit ALLOW rule cannot turn it into
        // arbitrary execution. Rules may still narrow read-only tools further.
        if(preset==PermissionPreset.READ_ONLY&&!readOnlyTool)return new ToolDecision.Deny("read-only workspace");
        for(ToolRule rule:rules){
            if(!rule.tool().equals("*")&&!rule.tool().equals(tool))continue;
            if(rule.pathGlob()!=null){
                JsonNode path=arguments.get("path");
                if(path==null||!path.isString())continue;
                Path normalized=Path.of(path.stringValue()).normalize();
                if(normalized.isAbsolute()||normalized.startsWith(".."))return new ToolDecision.Deny("invalid rule path");
                if(!FileSystems.getDefault().getPathMatcher("glob:"+rule.pathGlob()).matches(normalized))continue;
            }
            if(rule.commandRegex()!=null){
                JsonNode argv=arguments.get("argv");if(argv==null||!argv.isArray())continue;
                String encoded=argv.toString();if(encoded.length()>8192)return new ToolDecision.Deny("command too long");
                if(!Pattern.compile(rule.commandRegex()).matcher(encoded).matches())continue;
            }
            return switch(rule.decision()){
                case "ALLOW"->new ToolDecision.Allow();case "ASK"->new ToolDecision.Ask("tool policy rule");default->new ToolDecision.Deny("tool policy rule");
            };
        }
        if(readOnlyTool)return new ToolDecision.Allow();
        if(preset==PermissionPreset.FULL_ACCESS||Set.of("write","edit").contains(tool))return new ToolDecision.Allow();
        return new ToolDecision.Ask("arbitrary execution; not an OS sandbox");
    }
}
