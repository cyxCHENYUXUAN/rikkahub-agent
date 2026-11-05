# P4-A 上下文装配与压缩 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. 本工作区无 Git，跳过 commit 步骤，改用隔离快照构建与证据文件。

**Goal:** 交付稳定的系统提示词装配、token 计量与可重建的上下文压缩，为 P4-B/C 的技能与记忆 section 提供接缝。

**Architecture:** `agent-core/context` 保存纯 Java 的 section 装配、token 估算与压缩计划；backend 提供提示词数据与系统消息写入；压缩只追加 surface replace，不删除原始事件。P4-A 先做核心契约和规划器，再接产品提示词与手动/自动压缩入口。

**Tech Stack:** Java 17 / Jackson 3 / JUnit Jupiter 6 / SQLite。

**Spec:** ../specs/2026-11-04-p4-context-skills-memory-design.md（§D2、§D3）

## 当前进度（2026-11-04）

P4-A 已完成并纳入 P4 阶段验收：自动压缩（step 前、tool 配对保护）、手动压缩/放弃 HTTP、重启恢复未配对 start、`CompactionEndEvent.error`、`SystemPromptService` 装配与系统消息、以及 P4-B/C 所需的 skills/memory section 接缝。P4 全量隔离验收 26/26（P4 功能 22 + 原架构 4）通过；P4 临时测试已按用户要求删除，清理后仅原 4 项架构检查通过。`30-skills.md`/`40-memory.md` 模板已随 P4-B/C 落地。

## Global Constraints

- `agent-core` 不依赖 Spring、JDBC、servlet；只加 Jackson + SLF4J。
- 单写者、短事务；压缩事件与 surface 投影必须经唯一 `SessionCommitService`。
- 原始事件永不删除；压缩只改 `deriveMessages()` 的 surface。
- 提示词/技能/记忆正文是不可信数据，不能改变审批策略或工具权限。
- 未配对 `compaction/start` 重启后阻塞新的自动压缩，提供重试/放弃出口。
- 临时测试在阶段验收后删除；保留原 4 项架构检查。

## Review Focus

- 同名 section、相同 order 的稳定排序：结果必须确定，不能依赖输入顺序或 hash 顺序。
- 压缩切点与工具配对：不能把 `tool/call` 与其 `tool/result` 分到摘要两侧。
- 摘要失败/取消：必须写 `CompactionEndEvent.error`，原文 surface 不变，不重复付费调用。
- 系统提示词更新：`REPLACE_IN_PLACE` 必须遮蔽旧系统消息，`APPEND_TO_HISTORY` 必须保留旧版本。
- token 估算边界：空文本、纯空白、超大消息、CJK/emoji 不能抛异常或返回负值。

---

### Task 1: 核心 PromptSection 与 SystemPromptAssembler

**Files:**
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/PromptSection.java`
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/SystemPromptAssembler.java`
- Test: `agent-core/src/test/java/com/rikkahub/agent/context/P4ContextAcceptanceTest.java`

**Interfaces:**
- Produces:
  - `public record PromptSection(String name, int order, String content)`
  - `public static String SystemPromptAssembler.assemble(List<PromptSection> sections)`
  - `public static List<PromptSection> SystemPromptAssembler.ordered(List<PromptSection> sections)`

- [x] **Step 1: Write the failing tests**

```java
@Test void ordersByNameAndOrderAndIgnoresBlankSections() {
    String prompt = SystemPromptAssembler.assemble(List.of(
            new PromptSection("z", 10, "last"),
            new PromptSection("a", 10, "alpha"),
            new PromptSection("blank", 20, "  "),
            new PromptSection("first", 1, "first")));
    assertTrue(prompt.indexOf("first") < prompt.indexOf("alpha"));
    assertTrue(prompt.indexOf("alpha") < prompt.indexOf("last"));
    assertFalse(prompt.contains("blank"));
}
@Test void rejectsDuplicateSectionNames() {
    assertThrows(IllegalArgumentException.class, () -> SystemPromptAssembler.assemble(List.of(
            new PromptSection("same", 1, "a"), new PromptSection("same", 2, "b"))));
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `python -I .superpowers/.../verify.py p4a-red1 "-Dtest=P4ContextAcceptanceTest#ordersByNameAndOrderAndIgnoresBlankSections+rejectsDuplicateSectionNames" -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL (types/methods missing).

- [x] **Step 3: Implement the records and assembler**

`PromptSection`: validate non-null name, `name.matches("[A-Za-z0-9._-]{1,64}")`, non-null content, `order >= 0`.
`ordered`: copy, sort by `order` then `name`, reject duplicate names before sorting.
`assemble`: join non-blank contents with `"\n\n"`; do not add headings or executable syntax.

- [x] **Step 4: Run test to verify it passes**

Expected: PASS.

### Task 2: TokenMeter 与估算边界

**Files:**
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/TokenMeter.java`
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/HeuristicTokenMeter.java`
- Test: same acceptance test class.

**Interfaces:**
- Produces:
  - `public interface TokenMeter { int estimate(String text); default int estimate(List<Message> messages); }`
  - `public final class HeuristicTokenMeter implements TokenMeter`
- Rule: ASCII/whitespace run ≈ 4 chars/token; non-ASCII code point counts as 1 token; empty = 0; always non-negative and saturating at `Integer.MAX_VALUE`.

- [x] **Step 1: Write failing tests**

```java
@Test void tokenMeterIsConservativeAndNonNegative() {
    TokenMeter meter = new HeuristicTokenMeter();
    assertEquals(0, meter.estimate(""));
    assertTrue(meter.estimate("hello world") >= 1);
    assertTrue(meter.estimate("你好世界") >= 4);
    assertTrue(meter.estimate("a".repeat(10000)) >= 10000 / 4);
}
```

- [x] **Step 2: Run RED** — method missing.

- [x] **Step 3: Implement**

Use `text.codePoints()`; count code points > 127 as 1, others grouped/4; saturate with `Math.min(Integer.MAX_VALUE, ...)`.

- [x] **Step 4: Run GREEN.**

### Task 3: CompactionPlan 与规则化切点

**Files:**
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/CompactionPlan.java`
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/CompactionPlanner.java`
- Test: same acceptance test class.

**Interfaces:**
- `public record CompactionPlan(boolean triggered, long shadowedStartSeq, long shadowedEndSeq, List<Long> shadowedSeqs, List<Message> messagesToSummarize)`; empty plan = `CompactionPlan.none()`.
- `public static CompactionPlan CompactionPlanner.plan(List<Message> messages, ContextPolicy policy, TokenMeter meter)`.

Rules:
- Never shadow the first `SYSTEM` message; start after it.
- Keep the newest messages whose estimated tokens ≤ `keepRecentTokens`; shadow an older contiguous prefix.
- If the cut would separate an assistant message with tool calls from a following `TOOL` message, move the cut before the assistant message.
- If total estimate ≤ `compactionThresholdTokens`, return `none()`.
- Message ids `m-<seq>` map to shadowed seqs; a nonconforming id fails closed (no trigger) rather than guessing.

- [x] **Step 1: Write failing tests** covering trigger/no-trigger, system preservation, and tool-pair preservation.

- [x] **Step 2: Run RED.**

- [x] **Step 3: Implement.**

- [x] **Step 4: Run GREEN.**

### Task 4: 后端 PromptSectionProvider 与系统消息

**Files:**
- Create: `backend/src/main/java/com/rikkahub/features/chat/application/SystemPromptService.java`
- Create: `backend/src/main/resources/prompts/system/00-identity.md`, `10-environment.md`, `20-tools.md`, `90-guardrails.md`（`30-skills.md`/`40-memory.md` 随 P4-B/C）
- Modify: `backend/src/main/java/com/rikkahub/features/chat/application/ChatApplicationService.java`（创建会话时使用装配结果）
- Test: `backend/src/test/java/com/rikkahub/acceptance/P4PromptAcceptanceTest.java`

**Interfaces:**
- `SystemPromptService.render(AssistantProfile profile, Path cwd)` → `String`; loads classpath `prompts/system/*.md` read-only, renders whitelisted `${cwd}`/`${date}`/`${time}` placeholders, appends assistant systemPrompt as order 50.
- `ChatApplicationService.create` seeds `SystemMessageEvent` with the rendered prompt instead of only `assistant.systemPrompt`.

**Acceptance:** a created conversation's first system message contains identity + assistant prompt + guardrails; duplicate/unknown section fails closed; no SpEL evaluation.

### Task 5: 压缩事件错误字段与执行器

**Files:**
- Modify: `agent-core/src/main/java/com/rikkahub/agent/session/event/CompactionEndEvent.java`（增加可空 `error`，保留 1 参构造器）
- Create: `agent-core/src/main/java/com/rikkahub/agent/context/CompactionExecutor.java`（接收摘要调用结果，产出事件/ surface 替换描述）
- Test: same acceptance test class + `backend` manual endpoint test.

**Interfaces:**
- `CompactionEndEvent(String compactionId, String error)`; old JSON missing `error` decodes null.
- `CompactionExecutor` does not write the store; it returns the events and `SurfaceReplace(startSeq,endSeq)` for the writer to commit.

**Acceptance:** success writes start/summary/end(error=null)+replace; failure writes start/end(error)+no replace; original events remain.

### Task 6: 产品接入与验收

**Files:**
- Modify: `backend/.../chat/agent/ChatAgentService.java` / `AgentLoop` integration seam（自动压缩 before step）
- Modify: `backend/.../chat/api/ChatController.java`（手动压缩/放弃）
- Modify: `contracts/http/openapi.yaml`、`contracts/sse/events.yaml`、`docs/*`
- Test: temporary HTTP/SQLite acceptance.

**Acceptance:**
- Auto compaction triggers only over threshold and keeps tool pairs.
- Manual compaction only when idle; abandon clears an unpaired start.
- Restart marks unpaired compaction interrupted, does not auto-retry paid summary.
- Original events reconstructable after compaction; `deriveMessages()` shows summary replacement.
- Full `mvn clean verify` and executable JAR check; then delete P4-A temp tests and clean build.

---

## Self-Review

- Spec coverage: D2 (Tasks 1/4), D3 (Tasks 3/5/6), D1 split (P4-A boundary), testing/cleanup (Task 6).
- No P4-B/C/D/E implementation in this plan; they get separate plans after P4-A convergence.
- Type names consistent: `PromptSection`, `SystemPromptAssembler`, `TokenMeter`, `HeuristicTokenMeter`, `CompactionPlan`, `CompactionPlanner`, `CompactionExecutor`.
- External provider/PTY/OS sandbox remain out of scope.
