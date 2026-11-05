package com.rikkahub.plugins.modelproviders;

import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.rikkahub.agent.message.FinishReason;
import com.rikkahub.agent.message.StreamChunk;
import com.rikkahub.agent.message.TokenUsage;

/** Per-subscription SSE framing and OpenAI-compatible chunk normalization. */
final class StreamAssembler {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int MAX_LINE_CHARS = 1_048_576;
    private static final int MAX_EVENT_CHARS = 2_097_152;
    private static final int MAX_TOOL_CALLS = 128;
    private final Consumer<StreamChunk> output;
    private final StringBuilder event = new StringBuilder();
    private final Set<Integer> toolIndexes = new HashSet<>();
    private FinishReason finish;
    private boolean done;
    private boolean dataSeen;
    private boolean errorEvent;

    StreamAssembler(Consumer<StreamChunk> output) {
        this.output = output;
    }

    void read(Reader reader, BooleanSupplier active) throws IOException {
        char[] buffer = new char[4096];
        StringBuilder line = new StringBuilder();
        boolean first = true;
        boolean afterCarriageReturn = false;
        int count;
        while (active.getAsBoolean() && !done && (count = reader.read(buffer)) != -1) {
            for (int i = 0; i < count && active.getAsBoolean() && !done; i++) {
                char current = buffer[i];
                if (first) {
                    first = false;
                    if (current == '\uFEFF') {
                        continue;
                    }
                }
                if (current == '\n' && afterCarriageReturn) {
                    afterCarriageReturn = false;
                    continue;
                }
                afterCarriageReturn = current == '\r';
                if (current == '\n' || current == '\r') {
                    line(line.toString());
                    line.setLength(0);
                } else {
                    if (line.length() >= MAX_LINE_CHARS) {
                        throw new ProtocolException();
                    }
                    line.append(current);
                }
            }
        }
        if (!active.getAsBoolean()) {
            return;
        }
        if (!done) {
            if (!line.isEmpty()) {
                line(line.toString());
            }
            if (dataSeen) {
                dispatch();
            }
        }
        // Compatible providers may end at EOF after finish_reason instead of [DONE].
        if (finish == null) {
            throw new ProtocolException();
        }
    }

    private void line(String value) throws IOException {
        if (value.isEmpty()) {
            if (dataSeen) {
                dispatch();
            }
            errorEvent = false;
            return;
        }
        if (value.charAt(0) == ':') {
            return;
        }
        int colon = value.indexOf(':');
        String field = colon < 0 ? value : value.substring(0, colon);
        String content = colon < 0 ? "" : value.substring(colon + 1);
        if (content.startsWith(" ")) {
            content = content.substring(1);
        }
        if (field.equals("event")) {
            errorEvent = content.equals("error");
        } else if (field.equals("data")) {
            int separator = dataSeen ? 1 : 0;
            if ((long) event.length() + separator + content.length() > MAX_EVENT_CHARS) {
                throw new ProtocolException();
            }
            if (dataSeen) {
                event.append('\n');
            }
            dataSeen = true;
            event.append(content);
        }
    }

    private void dispatch() throws IOException {
        String data = event.toString();
        event.setLength(0);
        dataSeen = false;
        if (data.trim().equals("[DONE]")) {
            if (finish == null || errorEvent) {
                throw new ProtocolException();
            }
            done = true;
            return;
        }
        JsonNode root;
        try {
            root = JSON.readTree(data);
        } catch (JacksonException ex) {
            // Parser messages may contain fragments of the provider body. Discard them.
            throw new ProtocolException();
        }
        if (root == null || !root.isObject()) {
            throw new ProtocolException();
        }
        if (errorEvent || root.hasNonNull("error")) {
            String code = root.path("error").path("code").asString("");
            String type = root.path("error").path("type").asString("");
            throw new ProviderException(Set.of("rate_limit_exceeded", "server_error", "overloaded_error")
                    .contains(code) || Set.of("rate_limit_error", "server_error", "overloaded_error").contains(type));
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray()) {
            throw new ProtocolException();
        }
        for (JsonNode choice : choices) {
            if (!choice.isObject() || integer(choice.path("index"), 0) != 0) {
                throw new ProtocolException();
            }
            JsonNode delta = choice.get("delta");
            if (delta != null && !delta.isNull()) {
                if (!delta.isObject()) {
                    throw new ProtocolException();
                }
                if (finish == null) {
                    text(delta.get("content"), false);
                    text(delta.get("refusal"), false);
                    JsonNode reasoning = delta.hasNonNull("reasoning_content")
                            ? delta.get("reasoning_content") : delta.get("reasoning");
                    text(reasoning, true);
                    tools(delta.get("tool_calls"));
                    JsonNode legacy = delta.get("function_call");
                    if (legacy != null && !legacy.isNull()) {
                        tool(0, null, legacy);
                    }
                }
            }
            JsonNode reason = choice.get("finish_reason");
            if (reason != null && !reason.isNull()) {
                if (!reason.isString()) {
                    throw new ProtocolException();
                }
                FinishReason mapped = switch (reason.stringValue()) {
                    case "stop" -> FinishReason.STOP;
                    case "length" -> FinishReason.LENGTH;
                    case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
                    case "content_filter" -> FinishReason.CONTENT_FILTER;
                    default -> throw new ProtocolException();
                };
                if (finish != null && finish != mapped) {
                    throw new ProtocolException();
                }
                if (finish == null) {
                    finish = mapped;
                    output.accept(new StreamChunk.Finished(mapped));
                }
            }
        }
        JsonNode usage = root.get("usage");
        if (usage != null && !usage.isNull()) {
            if (!usage.isObject()) {
                throw new ProtocolException();
            }
            int input = integer(usage.get("prompt_tokens"), -1);
            int completion = integer(usage.get("completion_tokens"), -1);
            if (input < 0 || completion < 0 || (long) input + completion > Integer.MAX_VALUE) {
                throw new ProtocolException();
            }
            JsonNode details = usage.path("prompt_tokens_details");
            Integer cacheRead = optionalInteger(details.get("cached_tokens"));
            Integer cacheWrite = optionalInteger(details.get("cache_write_tokens"));
            output.accept(new StreamChunk.Usage(new TokenUsage(input, completion, cacheRead, cacheWrite)));
        }
    }

    private void text(JsonNode value, boolean reasoning) throws ProtocolException {
        String text = optionalText(value);
        if (text != null && !text.isEmpty()) {
            output.accept(reasoning ? new StreamChunk.ReasoningDelta(text) : new StreamChunk.TextDelta(text));
        }
    }

    private void tools(JsonNode calls) throws ProtocolException {
        if (calls == null || calls.isNull()) {
            return;
        }
        if (!calls.isArray() || calls.size() > MAX_TOOL_CALLS) {
            throw new ProtocolException();
        }
        for (JsonNode call : calls) {
            if (!call.isObject()) {
                throw new ProtocolException();
            }
            int index = integer(call.get("index"), -1);
            if (index < 0) {
                throw new ProtocolException();
            }
            tool(index, optionalText(call.get("id")), call.get("function"));
        }
    }

    private void tool(int index, String id, JsonNode function) throws ProtocolException {
        toolIndexes.add(index);
        if (toolIndexes.size() > MAX_TOOL_CALLS || (function != null && !function.isNull() && !function.isObject())) {
            throw new ProtocolException();
        }
        String name = function == null ? null : optionalText(function.get("name"));
        String arguments = function == null ? null : optionalText(function.get("arguments"));
        output.accept(new StreamChunk.ToolCallDelta(index, id, name, arguments));
    }

    private static String optionalText(JsonNode value) throws ProtocolException {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new ProtocolException();
        }
        return value.stringValue();
    }

    private static int integer(JsonNode value, int fallback) throws ProtocolException {
        if (value == null || value.isMissingNode()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ProtocolException();
        }
        return value.intValue();
    }

    private static Integer optionalInteger(JsonNode value) throws ProtocolException {
        if (value == null || value.isNull()) {
            return null;
        }
        int count = integer(value, -1);
        if (count < 0) {
            throw new ProtocolException();
        }
        return count;
    }

    static final class ProtocolException extends IOException {
        ProtocolException() {
            super("Invalid provider stream");
        }
    }

    static final class ProviderException extends IOException {
        private final boolean retryable;

        ProviderException(boolean retryable) {
            super("Provider stream error");
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
