package com.rikkahub.features.usage.application;

import java.util.List;

import com.rikkahub.platform.storage.TransactionRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only token/cost aggregation; usage_records remains append-only. */
@Service
public final class UsageApplicationService {
    private final JdbcClient jdbc;
    private final TransactionRunner transactions;

    public UsageApplicationService(JdbcClient jdbc, TransactionRunner transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Totals totals(String sessionId) {
        return transactions.read(() -> jdbc.sql("""
                SELECT coalesce(sum(input_tokens),0) AS input, coalesce(sum(output_tokens),0) AS output,
                       coalesce(sum(cache_read_tokens),0) AS cache_read,
                       coalesce(sum(cache_write_tokens),0) AS cache_write,
                       coalesce(sum(reasoning_tokens),0) AS reasoning,
                       coalesce(sum(cost_micros),0) AS cost, count(*) AS records
                FROM usage_records WHERE (:session IS NULL OR session_id=:session)
                """).param("session", sessionId)
                .query((row, number) -> new Totals(row.getLong("input"), row.getLong("output"),
                        row.getLong("cache_read"), row.getLong("cache_write"), row.getLong("reasoning"),
                        row.getLong("cost"), row.getLong("records"))).single());
    }

    public List<ModelUsage> byModel() {
        return transactions.read(() -> jdbc.sql("""
                SELECT provider, model, coalesce(sum(input_tokens),0) AS input,
                       coalesce(sum(output_tokens),0) AS output, coalesce(sum(cost_micros),0) AS cost,
                       count(*) AS records
                FROM usage_records GROUP BY provider, model ORDER BY provider, model
                """).query((row, number) -> new ModelUsage(row.getString("provider"), row.getString("model"),
                        row.getLong("input"), row.getLong("output"), row.getLong("cost"),
                        row.getLong("records"))).list());
    }

    public List<DayUsage> byDay() {
        return transactions.read(() -> jdbc.sql("""
                SELECT substr(created_at,1,10) AS day, coalesce(sum(input_tokens),0) AS input,
                       coalesce(sum(output_tokens),0) AS output, coalesce(sum(cost_micros),0) AS cost,
                       count(*) AS records
                FROM usage_records GROUP BY day ORDER BY day DESC LIMIT 366
                """).query((row, number) -> new DayUsage(row.getString("day"), row.getLong("input"),
                        row.getLong("output"), row.getLong("cost"), row.getLong("records"))).list());
    }

    public record Totals(long inputTokens, long outputTokens, long cacheReadTokens,
                         long cacheWriteTokens, long reasoningTokens, long costMicros, long records) {}
    public record ModelUsage(String provider, String model, long inputTokens, long outputTokens,
                             long costMicros, long records) {}
    public record DayUsage(String day, long inputTokens, long outputTokens, long costMicros, long records) {}
}
