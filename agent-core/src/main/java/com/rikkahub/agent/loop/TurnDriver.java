package com.rikkahub.agent.loop;

import java.util.Objects;

import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.session.Session;
import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.event.TurnEndReason;
import com.rikkahub.agent.session.event.TurnStart;

/** Turn-level calculations: numbering and the reason used to close a turn. */
public final class TurnDriver {

    TurnDriver() {
    }

    /** Turn numbers are derived from the durable log, not from local counters. */
    int nextTurn(Session session) {
        Objects.requireNonNull(session, "session");
        int turns = 0;
        for (SessionEvent event : session.events()) {
            if (event.data() instanceof TurnStart) {
                turns++;
            }
        }
        return turns + 1;
    }

    /** A model that hit the output ceiling closes the turn as {@code max_tokens}. */
    TurnEndReason endReason(FinishReason finishReason) {
        if (finishReason == FinishReason.LENGTH) {
            return new TurnEndReason.MaxTokens();
        }
        return new TurnEndReason.Completed();
    }
}
