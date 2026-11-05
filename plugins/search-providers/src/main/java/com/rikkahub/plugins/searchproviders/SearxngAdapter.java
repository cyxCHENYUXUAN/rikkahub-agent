package com.rikkahub.plugins.searchproviders;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.rikkahub.agent.port.SearchPort;
import com.rikkahub.agent.port.SearchResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Minimal SearXNG JSON adapter; no HTML scraping or redirect following. */
public final class SearxngAdapter implements SearchPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final String endpoint;

    public SearxngAdapter(HttpClient client, String baseUrl) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("search baseUrl must be HTTP(S)");
        }
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/search";
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(20, limit));
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&language=en&safesearch=1";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("search_unavailable");
            }
            JsonNode results = JSON.readTree(response.body()).path("results");
            List<SearchResult> hits = new ArrayList<>();
            for (JsonNode result : results) {
                if (hits.size() >= bounded) break;
                String title = result.path("title").asString("").trim();
                String link = result.path("url").asString("").trim();
                if (title.isEmpty() || link.isEmpty()) continue;
                hits.add(new SearchResult(title, link,
                        result.path("content").asString("").trim(), result.path("score").asDouble(0)));
            }
            return List.copyOf(hits);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("search_interrupted");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("search_unavailable");
        }
    }
}
