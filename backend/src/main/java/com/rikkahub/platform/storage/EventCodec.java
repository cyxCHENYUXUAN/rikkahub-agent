package com.rikkahub.platform.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import com.rikkahub.agent.session.SessionEvent;
import com.rikkahub.agent.session.SurfaceMeta;
import com.rikkahub.agent.session.SurfaceOp;
import com.rikkahub.agent.session.event.SessionEventPayload;
import org.springframework.stereotype.Component;

/**
 * Stable event JSON plus canonical UTC timestamps for SQL lease comparisons.
 */
@Component
public final class EventCodec {
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final ObjectMapper json;

    public EventCodec(ObjectMapper mapper) {
        json = mapper.rebuild().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("cannot encode stored JSON", ex);
        }
    }

    public <T> String encode(T value, TypeReference<T> type) {
        try {
            return json.writerFor(type).writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("cannot encode stored JSON", ex);
        }
    }

    public <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid stored JSON", ex);
        }
    }

    public <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid stored JSON", ex);
        }
    }

    public String eventType(SessionEventPayload data) {
        String type = json.valueToTree(data).path("type").asString();
        if (type.isBlank()) {
            throw new IllegalArgumentException("event discriminator is missing");
        }
        return type;
    }

    public SessionEvent readEvent(ResultSet row) throws SQLException {
        SessionEventPayload data = decode(row.getString("data_json"), SessionEventPayload.class);
        if (!eventType(data).equals(row.getString("event_type"))) {
            throw new IllegalArgumentException("event discriminator does not match its indexed type");
        }
        String op = row.getString("surface_op_json");
        SurfaceMeta surface = op == null ? null
                : new SurfaceMeta(decode(op, SurfaceOp.class),
                        decode(row.getString("source_event_seqs_json"), new TypeReference<List<Long>>() {
                        }));
        return new SessionEvent(row.getLong("seq"), Instant.parse(row.getString("event_time")),
                data, surface, row.getInt("ignorable") != 0);
    }

    public static String timestamp(Instant value) {
        return TIME.format(value);
    }
}
