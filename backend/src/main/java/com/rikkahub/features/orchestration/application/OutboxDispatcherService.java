package com.rikkahub.features.orchestration.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import com.rikkahub.platform.storage.DurableWorkQueue;
import com.rikkahub.platform.storage.DurableWorkQueue.Work;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Reliable outbox dispatcher. Delivers outbox notifications to external Webhooks / IM endpoints.
 * Uses stored-work-first lease fencing to prevent duplicate delivery or lost events.
 */
@Service
public final class OutboxDispatcherService {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final DurableWorkQueue workQueue;
    private final Environment environment;
    private final HttpClient client;

    public OutboxDispatcherService(DurableWorkQueue workQueue, Environment environment) {
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    /**
     * Attempts to claim and deliver one outbox work item.
     *
     * @return true if an item was claimed and processed; false if queue is empty
     */
    public boolean dispatchNext() {
        Work item = workQueue.claim("outbox", LEASE);
        if (item == null) {
            return false;
        }

        String webhookUrl = environment.getProperty("RIKKAHUB_OUTBOX_WEBHOOK_URL");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            webhookUrl = environment.getProperty("WEBHOOK_OUTBOX_URL");
        }

        if (webhookUrl == null || webhookUrl.isBlank()) {
            // No external webhook configured: mark as locally satisfied/logged
            workQueue.settle("outbox", item, true, "local_delivered_no_endpoint");
            return true;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .header("X-RikkaHub-Event", item.kind())
                    .header("X-RikkaHub-Work-Id", item.id())
                    .POST(HttpRequest.BodyPublishers.ofString(item.payloadJson(), StandardCharsets.UTF_8))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                workQueue.settle("outbox", item, true, "delivered: " + response.statusCode());
            } else {
                workQueue.settle("outbox", item, false, "http_status_" + response.statusCode());
            }
        } catch (Exception e) {
            workQueue.settle("outbox", item, false, "delivery_error: " + e.getMessage());
        }

        return true;
    }

    /**
     * Drains pending outbox items up to limit.
     *
     * @param maxBatch maximum number of items to dispatch
     * @return number of dispatched items
     */
    public int dispatchAll(int maxBatch) {
        int count = 0;
        while (count < maxBatch && dispatchNext()) {
            count++;
        }
        return count;
    }
}
