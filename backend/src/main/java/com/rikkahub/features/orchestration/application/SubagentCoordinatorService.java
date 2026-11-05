package com.rikkahub.features.orchestration.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.rikkahub.agent.api.CancellationToken;
import com.rikkahub.agent.api.InboxItem;
import com.rikkahub.agent.api.InboxTarget;
import com.rikkahub.agent.message.TextBlock;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import com.rikkahub.agent.port.PendingInput;
import com.rikkahub.agent.port.SessionStore;
import com.rikkahub.agent.session.InputId;
import com.rikkahub.agent.session.NewSessionEvent;
import com.rikkahub.agent.session.SessionCommit;
import com.rikkahub.agent.session.SessionFormat;
import com.rikkahub.agent.session.SessionHeader;
import com.rikkahub.agent.session.SessionId;
import com.rikkahub.agent.session.SurfaceMeta;
import com.rikkahub.agent.session.SurfaceOp;
import com.rikkahub.agent.session.event.SystemMessageEvent;
import com.rikkahub.features.assistants.application.AssistantApplicationService;
import com.rikkahub.features.assistants.application.AssistantApplicationService.AssistantProfile;
import com.rikkahub.features.chat.agent.ChatAgentService;
import com.rikkahub.features.chat.application.SystemPromptService;
import com.rikkahub.platform.storage.EventCodec;
import com.rikkahub.platform.storage.JdbcInboxStore;
import com.rikkahub.platform.storage.SessionCommitService;
import com.rikkahub.platform.storage.StoragePaths;
import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Superagent - Subagent network coordinator.
 * Spawns isolated, sandboxed subagent tasks with independent session headers and restricted workspace paths.
 * Enforces fail-closed task cancellation cascading from parent sessions.
 */
@Service
public final class SubagentCoordinatorService {
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, CANCELLED, FAILED }

    public record SubagentTask(String taskId, String parentSessionId, String subagentSessionId,
                               String assistantId, String instruction, TaskStatus status,
                               String result, String createdAt) {}

    private final Map<String, SubagentTask> tasks = new ConcurrentHashMap<>();

    private final AssistantApplicationService assistants;
    private final ChatAgentService chatAgents;
    private final SessionStore sessionStore;
    private final SessionCommitService commits;
    private final JdbcInboxStore inbox;
    private final TransactionRunner transactions;
    private final JdbcClient jdbc;
    private final StoragePaths paths;
    private final Clock clock;
    private final IdGenerator ids;
    private final SystemPromptService prompts;

    public SubagentCoordinatorService(AssistantApplicationService assistants,
                                     ChatAgentService chatAgents,
                                     SessionStore sessionStore,
                                     SessionCommitService commits,
                                     JdbcInboxStore inbox,
                                     TransactionRunner transactions,
                                     JdbcClient jdbc,
                                     StoragePaths paths,
                                     Clock clock,
                                     IdGenerator ids,
                                     SystemPromptService prompts) {
        this.assistants = assistants;
        this.chatAgents = chatAgents;
        this.sessionStore = sessionStore;
        this.commits = commits;
        this.inbox = inbox;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.paths = paths;
        this.clock = clock;
        this.ids = ids;
        this.prompts = prompts;
    }

    /**
     * Delegates a specialized task to a subagent assistant in an isolated sub-session.
     */
    public SubagentTask delegate(String parentSessionId, String assistantId, String instruction, String background) {
        Objects.requireNonNull(parentSessionId, "parentSessionId");
        Objects.requireNonNull(assistantId, "assistantId");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("task instruction must not be blank");
        }

        AssistantProfile profile = assistants.get(assistantId);
        String taskId = ids.newId("subtask");
        SessionId subSession = SessionId.of("subagent-" + taskId);
        Path workspace = paths.dataDirectory().resolve("workspace/subagents").resolve(taskId).normalize();

        String now = EventCodec.timestamp(clock.now());
        SubagentTask task = new SubagentTask(taskId, parentSessionId, subSession.value(), assistantId,
                instruction, TaskStatus.PENDING, null, now);
        tasks.put(taskId, task);

        // Commit initial isolated session header with restricted workspace
        transactions.write(() -> {
            boolean exists = jdbc.sql("SELECT count(*) FROM agent_sessions WHERE id=:id")
                    .param("id", subSession.value()).query(Integer.class).single() > 0;
            if (!exists) {
                SessionHeader header = new SessionHeader(
                        subSession,
                        null,
                        workspace,
                        SessionFormat.CURRENT,
                        "subagent",
                        assistantId,
                        Map.of("taskId", taskId, "parentSessionId", parentSessionId),
                        clock.now()
                );
                String systemPrompt = prompts.render(profile, workspace)
                        + "\n\n你作为被委派的专业子智能体（Subagent）执行。请在受限子工作区内专心完成任务，并将最终结论清晰总结输出。";
                commits.commitInCurrentTransaction(new SessionCommit(
                        subSession,
                        0,
                        header,
                        List.of(new NewSessionEvent(new SystemMessageEvent(systemPrompt),
                                new SurfaceMeta(new SurfaceOp.SurfaceAppend(), List.of()), false)),
                        List.of(), List.of(), List.of(), null, null
                ));
            }

            // Enqueue subagent task input
            String taskPrompt = (background != null && !background.isBlank() ? "背景上下文：\n" + background + "\n\n" : "")
                    + "子任务指令：\n" + instruction;
            String inputId = "subtask-input-" + taskId;
            inbox.enqueueInCurrentTransaction(new PendingInput(
                    new InputId(inputId),
                    subSession,
                    InboxTarget.NEXT_TURN,
                    new InboxItem(inputId, List.of(new TextBlock(taskPrompt)), Map.of("taskId", taskId)),
                    clock.now()
            ));
            return null;
        });

        tasks.put(taskId, new SubagentTask(taskId, parentSessionId, subSession.value(), assistantId,
                instruction, TaskStatus.RUNNING, null, now));
        return tasks.get(taskId);
    }

    /**
     * Checks subagent task status.
     */
    public SubagentTask getTask(String taskId) {
        SubagentTask task = tasks.get(taskId);
        if (task == null) {
            throw new NoSuchElementException("subagent task not found");
        }
        return task;
    }

    /**
     * Completes and records result for a subagent task.
     */
    public SubagentTask completeTask(String taskId, String result) {
        SubagentTask existing = getTask(taskId);
        SubagentTask completed = new SubagentTask(
                existing.taskId(), existing.parentSessionId(), existing.subagentSessionId(),
                existing.assistantId(), existing.instruction(), TaskStatus.COMPLETED,
                result, existing.createdAt()
        );
        tasks.put(taskId, completed);
        return completed;
    }

    /**
     * Cancels a subagent task and aborts its underlying session agent.
     */
    public SubagentTask cancelTask(String taskId) {
        SubagentTask existing = getTask(taskId);
        chatAgents.cancel(SessionId.of(existing.subagentSessionId()));
        SubagentTask cancelled = new SubagentTask(
                existing.taskId(), existing.parentSessionId(), existing.subagentSessionId(),
                existing.assistantId(), existing.instruction(), TaskStatus.CANCELLED,
                "Cancelled by parent", existing.createdAt()
        );
        tasks.put(taskId, cancelled);
        return cancelled;
    }

    public List<SubagentTask> listTasks(String parentSessionId) {
        if (parentSessionId == null) {
            return List.copyOf(tasks.values());
        }
        List<SubagentTask> list = new ArrayList<>();
        for (SubagentTask task : tasks.values()) {
            if (parentSessionId.equals(task.parentSessionId())) {
                list.add(task);
            }
        }
        return list;
    }
}
