package com.rikkahub.agent.context;

import java.util.List;

import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.message.Message;

/**
 * Host-provided compaction. Called on the session writer before a step; returns
 * null when no compaction is needed. Implementations must not write the store;
 * the caller commits the returned events in one session commit.
 */
public interface CompactionService {
    CompactionExecution compact(AgentContext context, List<Message> messages, int turn, int step);
}
