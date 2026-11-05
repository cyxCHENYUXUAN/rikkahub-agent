package com.rikkahub.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rikkahub.agent.config.ContextPolicy;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.message.MessageRole;
import com.rikkahub.agent.message.ToolCallBlock;

/**
 * Chooses an old contiguous prefix to summarize while keeping the leading
 * system message and the newest window. Assistant tool calls and their tool
 * results are grouped so a cut never separates them.
 */
public final class CompactionPlanner {
    private static final Pattern MESSAGE_SEQ = Pattern.compile("m-(\\d+)");

    private CompactionPlanner() {
    }

    public static CompactionPlan plan(List<Message> messages, ContextPolicy policy, TokenMeter meter) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(meter, "meter");
        if (messages.isEmpty() || meter.estimate(messages) <= policy.compactionThresholdTokens()) {
            return CompactionPlan.none();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).role() == MessageRole.SYSTEM) {
            start++;
        }
        if (start >= messages.size() - 1) {
            return CompactionPlan.none();
        }
        List<List<Message>> groups = groups(messages, start);
        long kept = 0;
        int keepFrom = groups.size();
        for (int index = groups.size() - 1; index >= 0; index--) {
            long cost = meter.estimate(groups.get(index));
            if (kept + cost > policy.keepRecentTokens() && keepFrom < groups.size()) {
                break;
            }
            kept += cost;
            keepFrom = index;
        }
        if (keepFrom <= 0) {
            return CompactionPlan.none();
        }
        List<Message> shadowed = new ArrayList<>();
        for (List<Message> group : groups.subList(0, keepFrom)) {
            shadowed.addAll(group);
        }
        List<Long> seqs = new ArrayList<>(shadowed.size());
        for (Message message : shadowed) {
            Matcher matcher = MESSAGE_SEQ.matcher(message.id());
            if (!matcher.matches()) {
                // A non-session message cannot be addressed safely in the event log.
                return CompactionPlan.none();
            }
            try {
                seqs.add(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException overflow) {
                return CompactionPlan.none();
            }
        }
        long first = seqs.get(0);
        long last = seqs.get(seqs.size() - 1);
        for (long seq : seqs) {
            first = Math.min(first, seq);
            last = Math.max(last, seq);
        }
        return new CompactionPlan(true, first, last, seqs, shadowed);
    }

    private static List<List<Message>> groups(List<Message> messages, int start) {
        List<List<Message>> groups = new ArrayList<>();
        for (int index = start; index < messages.size(); ) {
            Message message = messages.get(index);
            List<Message> group = new ArrayList<>();
            group.add(message);
            int next = index + 1;
            if (message.role() == MessageRole.ASSISTANT && hasToolCall(message)) {
                while (next < messages.size() && messages.get(next).role() == MessageRole.TOOL) {
                    group.add(messages.get(next));
                    next++;
                }
            }
            groups.add(group);
            index = next;
        }
        return groups;
    }

    private static boolean hasToolCall(Message message) {
        return message.content().stream().anyMatch(ToolCallBlock.class::isInstance);
    }
}
