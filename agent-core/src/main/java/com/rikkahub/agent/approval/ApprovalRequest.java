package com.rikkahub.agent.approval;

import java.time.Instant;
import java.util.Objects;

public record ApprovalRequest(String id,String sessionId,String callId,String toolName,String argumentsJson,
                              String reason,Instant createdAt,int turn,Instant expiresAt) {
    public ApprovalRequest {
        Objects.requireNonNull(id);Objects.requireNonNull(sessionId);Objects.requireNonNull(callId);Objects.requireNonNull(toolName);
        Objects.requireNonNull(argumentsJson);Objects.requireNonNull(createdAt);
        expiresAt=expiresAt==null?createdAt.plusSeconds(120):expiresAt;
        if(turn<0||!expiresAt.isAfter(createdAt))throw new IllegalArgumentException("invalid approval lifetime");
    }
    public String argumentsDigest(){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(argumentsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public ApprovalRequest(String id,String sessionId,String callId,String toolName,String argumentsJson,String reason,Instant createdAt){
        this(id,sessionId,callId,toolName,argumentsJson,reason,createdAt,0,createdAt.plusSeconds(120));
    }
}
