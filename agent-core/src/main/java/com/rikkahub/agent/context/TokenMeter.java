package com.rikkahub.agent.context;

import java.util.List;

import com.rikkahub.agent.message.Message;

/** Estimates text size for compaction decisions; never returns a negative value. */
public interface TokenMeter {
    int estimate(String text);

    default int estimate(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            total += estimate(message.text());
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }
}
