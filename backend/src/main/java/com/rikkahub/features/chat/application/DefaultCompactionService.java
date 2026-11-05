package com.rikkahub.features.chat.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.rikkahub.agent.api.AgentContext;
import com.rikkahub.agent.context.CompactionExecution;
import com.rikkahub.agent.context.CompactionExecutor;
import com.rikkahub.agent.context.CompactionPlan;
import com.rikkahub.agent.context.CompactionPlanner;
import com.rikkahub.agent.context.CompactionService;
import com.rikkahub.agent.context.HeuristicTokenMeter;
import com.rikkahub.agent.context.TokenMeter;
import com.rikkahub.agent.message.Message;
import com.rikkahub.agent.model.LlmRequest;
import com.rikkahub.agent.model.ModelRef;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.features.providers.application.ProviderApplicationService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Product compaction: plans locally, performs one non-retried summary call, and
 * returns events for the session writer to commit. No database connection is
 * held while the model stream is active.
 */
@Service
public final class DefaultCompactionService implements CompactionService {
    private final ProviderApplicationService providers;
    private final Clock clock;
    private final IdGenerator ids;
    private final TokenMeter meter = new HeuristicTokenMeter();
    private final String summaryPrompt;

    public DefaultCompactionService(ProviderApplicationService providers, Clock clock, IdGenerator ids) {
        this.providers = providers;
        this.clock = clock;
        this.ids = ids;
        try {
            this.summaryPrompt = new ClassPathResource("prompts/compression/summary.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("compaction prompt is unavailable", failure);
        }
    }

    @Override
    public CompactionExecution compact(AgentContext context, List<Message> messages, int turn, int step) {
        CompactionPlan plan = CompactionPlanner.plan(messages, context.config().context(), meter);
        if (!plan.triggered()) {
            return null;
        }
        String compactionId = ids.newId("cmp");
        if (context.cancellation().isCancelled()) {
            return CompactionExecutor.failure(compactionId, 1, "compaction_cancelled");
        }
        ModelRef model = context.config().model();
        List<Message> requestMessages = new ArrayList<>();
        requestMessages.add(Message.system("m-summary", summaryPrompt, clock.now()));
        requestMessages.addAll(plan.messagesToSummarize());
        LlmRequest request = new LlmRequest(ids.newId("req"), model, null, requestMessages, List.of(),
                context.config().generation(),
                Map.of("purpose", "compaction", "turn", turn, "step", step), clock.now());
        ModelTextCollector.Result result = ModelTextCollector.collect(
                providers.adapter(model.provider()), request, context.cancellation(),
                context.config().turnTimeout());
        if (!result.succeeded()) {
            return CompactionExecutor.failure(compactionId, 1, "compaction_" + result.error());
        }
        return CompactionExecutor.success(compactionId, 1, plan, result.text(), result.usage(),
                model.provider(), model.model(), ids.newId("input"));
    }
}
