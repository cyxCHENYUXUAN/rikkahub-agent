package com.rikkahub.platform.recovery;

public record RecoveryReport(int resetInbox, int cancelledInbox, int interruptedSessions, int pairedApprovals) { }
