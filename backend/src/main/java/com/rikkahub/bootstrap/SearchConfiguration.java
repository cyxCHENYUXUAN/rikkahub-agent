package com.rikkahub.bootstrap;

import java.net.http.HttpClient;
import java.time.Duration;

import com.rikkahub.agent.port.SearchPort;
import com.rikkahub.plugins.searchproviders.SearxngAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Search is configuration-gated; absence is an explicit unavailable capability. */
@Configuration
public class SearchConfiguration {
    @Bean
    SearchPort searchPort(@Value("${rikkahub.search.searxng-url:}") String searxngUrl) {
        if (searxngUrl == null || searxngUrl.isBlank()) {
            return (query, limit) -> {
                throw new IllegalStateException("search_unavailable: no search provider is configured");
            };
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return new SearxngAdapter(client, searxngUrl);
    }
}
