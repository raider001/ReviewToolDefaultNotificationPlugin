package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.requests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.lwdi.DI;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfig;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfigManager;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientW;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewSummary;
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
 * <p>Config is read from the {@link IndexerConfigManager} on each call so changes
 * take effect immediately without requiring a restart.
 */
public class IndexerRestClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerRestClient.class);

    private final HttpClient http;
    private final IndexerConfigManager configManager;

    @DI
    public IndexerRestClient(HttpClientW httpClient, IndexerConfigManager configManager) {
        this.http = httpClient.get();
        this.configManager = configManager;
    }

    public List<ReviewSummary> fetchReviews() {
        IndexerConfig config = configManager.get();
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

    public List<RepositoryDescriptor> fetchRepositories() {
        IndexerConfig config = configManager.get();
        if (config.indexerUrl() == null || config.indexerUrl().isBlank()) {
            return Collections.emptyList();
        }
        String url = config.indexerUrl() + "/repositories";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET();
        if (!config.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.bearerToken());
        }
        try {
            LOGGER.debug("Fetching repositories from {}", url);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("GET /repositories returned {}", response.statusCode());
                return Collections.emptyList();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
            List<RepositoryDescriptor> result = new ArrayList<>(items.size());
            for (JsonElement el : items) {
                JsonObject obj = el.getAsJsonObject();
                String owner = getString(obj, "owner");
                String repo  = getString(obj, "repository");
                String repoUrl = getString(obj, "url");
                if (owner != null && repo != null) {
                    result.add(new RepositoryDescriptor(owner + "/" + repo, repoUrl));
                }
            }
            LOGGER.debug("Fetched {} repository/repositories from indexer", result.size());
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch repositories from indexer: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<String> fetchBranchesFromRepository(String repository) {
        return fetchBranchesInternal(repository);
    }

    public List<String> fetchBranches() {
        return fetchBranchesInternal(null);
    }

    private List<String> fetchBranchesInternal(String repository) {
        IndexerConfig config = configManager.get();
        if (config.indexerUrl() == null || config.indexerUrl().isBlank()) {
            return Collections.emptyList();
        }
        String url = config.indexerUrl() + "/branches?limit=500"
                + (repository != null && !repository.isBlank() ? "&repository=" + repository : "");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET();
        if (!config.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.bearerToken());
        }
        try {
            LOGGER.debug("Fetching branches from {}", url);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("GET /branches returned {}", response.statusCode());
                return Collections.emptyList();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray arr = root.has("branches") ? root.getAsJsonArray("branches") : new JsonArray();
            List<String> branches = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (!el.isJsonNull()) branches.add(el.getAsString());
            }
            LOGGER.debug("Fetched {} branch(es) from indexer", branches.size());
            return Collections.unmodifiableList(branches);
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch branches from indexer: {}", e.getMessage());
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

                List<RepositoryDescriptor> repos = new ArrayList<>();
                if (item.has("repositories") && item.get("repositories").isJsonArray()) {
                    for (JsonElement repoEl : item.getAsJsonArray("repositories")) {
                        JsonObject repoObj = repoEl.getAsJsonObject();
                        String repo = getString(repoObj, "repository");
                        String repoUrl = getString(repoObj, "repository_url");
                        if (repo != null) {
                            repos.add(new RepositoryDescriptor(repo, repoUrl));
                        }
                    }
                }
                if (reviewId != null) {
                    result.add(new ReviewSummary(reviewId, status, reviewBranch, baseBranch,
                            Collections.unmodifiableList(repos)));
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
}
