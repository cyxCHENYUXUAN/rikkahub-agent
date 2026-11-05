package com.rikkahub.agent.approval;

public enum ApprovalPolicy {
    /** Ask the host before every side-effecting tool call. */
    ASK,
    /** Never allow: the tool call is rejected without consulting a broker. */
    NEVER
}
