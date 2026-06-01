package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads {@link IndexerConfig} from the plugin configuration file.
 *
 * <p>The file path is resolved from the system property {@code srt.notification.config},
 * falling back to {@code repositories.json} in the working directory.
 *
 * <p>Expected JSON format:
 * <pre>{@code
 * {
 *   "indexerUrl":  "http://localhost:8765",
 *   "bearerToken": "my-token",
 *   "repositories": [
 *     { "name": "owner/repo", "location": "https://github.com/owner/repo.git" }
 *   ]
 * }
 * }</pre>
 */
public class IndexerConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerConfigLoader.class);
    private static final String CONFIG_PROPERTY = "srt.notification.config";
    private static final String DEFAULT_CONFIG_NAME = "repositories.json";

    private final Gson gson = new Gson();

    /**
     * Loads the configuration from disk, returning {@link IndexerConfig#defaultConfig()} on any error.
     *
     * @return the loaded configuration, never {@code null}
     */
    public IndexerConfig load() {
        Path configPath = resolveConfigPath();
        if (!Files.exists(configPath)) {
            LOGGER.warn("Configuration file not found: {}", configPath);
            return IndexerConfig.defaultConfig();
        }
        try {
            String content = Files.readString(configPath);
            return parse(content);
        } catch (IOException e) {
            LOGGER.error("Error reading configuration file: {}", configPath, e);
            return IndexerConfig.defaultConfig();
        }
    }

    /**
     * Parses raw JSON content into an {@link IndexerConfig}.
     *
     * @param content raw JSON string
     * @return parsed config, falling back to defaults on parse failure
     */
    public IndexerConfig parse(String content) {
        try {
            JsonConfig raw = gson.fromJson(content, JsonConfig.class);
            if (raw == null) {
                return IndexerConfig.defaultConfig();
            }
            return toIndexerConfig(raw);
        } catch (Exception e) {
            LOGGER.error("Error parsing configuration: {}", e.getMessage());
            return IndexerConfig.defaultConfig();
        }
    }

    private IndexerConfig toIndexerConfig(JsonConfig raw) {
        String url = raw.indexerUrl != null ? raw.indexerUrl.trim() : "http://localhost:8765";
        String token = raw.bearerToken != null ? raw.bearerToken.trim() : "";
        List<IndexerConfig.RepositoryEntry> repos = buildRepositories(raw);
        return new IndexerConfig(url, token, repos);
    }

    private List<IndexerConfig.RepositoryEntry> buildRepositories(JsonConfig raw) {
        if (raw.repositories == null) {
            return List.of();
        }
        return raw.repositories.stream()
                .filter(r -> r.name != null && !r.name.isBlank())
                .filter(r -> r.location != null && !r.location.isBlank())
                .map(r -> new IndexerConfig.RepositoryEntry(r.name.trim(), r.location.trim()))
                .toList();
    }

    /**
     * Returns the resolved filesystem path to the configuration file.
     *
     * @return absolute normalised path
     */
    public Path resolveConfigPath() {
        String configured = System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_NAME);
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static class JsonConfig {
        String indexerUrl;
        String bearerToken;
        List<JsonRepo> repositories;
    }

    private static class JsonRepo {
        String name;
        String location;
    }
}

