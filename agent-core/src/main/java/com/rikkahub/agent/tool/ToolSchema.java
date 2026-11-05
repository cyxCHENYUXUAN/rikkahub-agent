package com.rikkahub.agent.tool;

import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The model-visible description of a tool. Only name, description and the JSON
 * schema parameters are exposed to the model.
 */
public record ToolSchema(String name, String description, JsonNode parameters) {
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    public ToolSchema {
        Objects.requireNonNull(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid tool name: " + name);
        }
        parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
    }

    /** Jackson container nodes are mutable, so callers receive a defensive copy. */
    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }
}
