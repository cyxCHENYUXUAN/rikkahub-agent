package com.rikkahub.agent.model;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable snapshots of JSON-shaped option and metadata values. */
final class FrozenValues {
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private FrozenValues() {
    }

    static Map<String, Object> map(Map<String, Object> values, String name) {
        Objects.requireNonNull(values, name);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) freeze(values, new IdentityHashMap<>());
        // Retain the record contract that top-level map keys and values are non-null.
        return Map.copyOf(frozen);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("frozen values must not contain cycles");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (!(key instanceof String text)) {
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    }
                    copy.put(text, freeze(item, ancestors));
                });
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(item -> copy.add(freeze(item, ancestors)));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Set<?> set) {
                List<Object> ordered = new ArrayList<>(set.size());
                set.forEach(item -> ordered.add(freeze(item, ancestors)));
                // A set has no input ordering; give its JSON array a stable ordering.
                ordered.sort(Comparator.comparing(FrozenValues::canonicalJson));
                return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
            }
            if (value.getClass().isArray()) {
                List<Object> copy = new ArrayList<>(Array.getLength(value));
                for (int i = 0; i < Array.getLength(value); i++) {
                    copy.add(freeze(Array.get(value, i), ancestors));
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof JsonNode node) {
                if (node.isObject()) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    node.properties().forEach(entry -> copy.put(entry.getKey(), freeze(entry.getValue(), ancestors)));
                    return Collections.unmodifiableMap(copy);
                }
                if (node.isArray()) {
                    List<Object> copy = new ArrayList<>(node.size());
                    node.forEach(item -> copy.add(freeze(item, ancestors)));
                    return Collections.unmodifiableList(copy);
                }
                if (node.isNull()) {
                    return null;
                }
                if (node.isString() || node.isBinary()) {
                    return node.asString();
                }
                if (node.isBoolean()) {
                    return node.booleanValue();
                }
                if (node.isNumber()) {
                    return freeze(node.numberValue(), ancestors);
                }
            }
            throw new IllegalArgumentException("unsupported frozen JSON value: " + value.getClass().getName());
        } finally {
            ancestors.remove(value);
        }
    }

    private static String canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("cannot serialize frozen JSON value", ex);
        }
    }
}
