package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calls the Central Indexer REST endpoints on behalf of the notification plugin.
 *
 * <p>Used at startup to load the initial review list via {@code GET /reviews} before
 * opening the SSE stream.
 */
public class IndexerRestClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerRestClient.class);

    private final HttpClient http;

    public IndexerRestClient() {
        this(HttpClient.newHttpClient());
    }

    IndexerRestClient(HttpClient http) {
        this.http = http;
    }

    /**
     * Fetches the current review list from the indexer.
     *
     * @param config plugin configuration supplying the indexer URL and bearer token
     * @return list of review summaries; empty on error or when the indexer is unreachable
     */
    public List<ReviewSummary> fetchReviews(IndexerConfig config) {
        if (config.indexerUrl() == null || config.indexerUrl().isBlank()) {
            return Collections.emptyList();
        }
        String url = config.indexerUrl() + "/reviews";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET();
        if (!config.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.bearerToken());
        }
        try {
            LOGGER.debug("Fetching reviews from {}", url);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("GET /reviews returned {}", response.statusCode());
                return Collections.emptyList();
            }
            List<ReviewSummary> reviews = parseReviews(response.body());
            LOGGER.debug("Fetched {} review(s) from indexer", reviews.size());
            return reviews;
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch reviews from indexer: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ReviewSummary> parseReviews(String body) {
        List<ReviewSummary> result = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                String reviewId = getString(item, "review_id");
                String status = getString(item, "status");
                String reviewBranch = getString(item, "review_branch");
                String baseBranch = getString(item, "base_branch");

                List<RepositoryRef> repos = new ArrayList<>();
                if (item.has("repositories") && item.get("repositories").isJsonArray()) {
                    for (JsonElement repoEl : item.getAsJsonArray("repositories")) {
                        JsonObject repoObj = repoEl.getAsJsonObject();
                        String repo = getString(repoObj, "repository");
                        String repoUrl = getString(repoObj, "repository_url");
                        if (repo != null) {
                            repos.add(new RepositoryRef(repo, repoUrl));
                        }
                    }
                }
                if (reviewId != null) {
                    result.add(new ReviewSummary(reviewId, status, reviewBranch, baseBranch, Collections.unmodifiableList(repos)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse /reviews response: {}", e.getMessage());
        }
        return result;
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /**
     * A single review entry returned by {@code GET /reviews}.
     */
    public record ReviewSummary(
            String reviewId,
            String status,
            String reviewBranch,
            String baseBranch,
            List<RepositoryRef> repositories) {}

    /**
     * A repository entry within a review summary.
     *
     * @param repository  canonical repository identifier ({@code "owner/repo"})
     * @param repositoryUrl canonical git URL for fetching content from this repository
     */
    public record RepositoryRef(String repository, String repositoryUrl) {}
}
