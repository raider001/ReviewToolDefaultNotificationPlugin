package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persists {@link IndexerConfig} to the plugin configuration file.
 *
 * <p>The file path is resolved using the same rules as {@link IndexerConfigLoader}.
 */
public class IndexerConfigSaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerConfigSaver.class);
    private static final String CONFIG_PROPERTY = "srt.notification.config";
    private static final String DEFAULT_CONFIG_NAME = "repositories.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Saves the given {@link IndexerConfig} to disk.
     *
     * @param config the configuration to persist
     * @throws IOException if the file cannot be written
     */
    public void save(IndexerConfig config) throws IOException {
        JsonConfig data = toJsonConfig(config);
        Path configPath = resolveConfigPath();
        Files.writeString(configPath, gson.toJson(data));
        LOGGER.info("Saved indexer configuration to: {}", configPath);
    }

    private JsonConfig toJsonConfig(IndexerConfig config) {
        JsonConfig data = new JsonConfig();
        data.indexerUrl = config.indexerUrl();
        data.bearerToken = config.bearerToken();
        data.repositories = config.repositories().stream()
                .map(r -> {
                    JsonRepo repo = new JsonRepo();
                    repo.name = r.name();
                    repo.location = r.location();
                    return repo;
                })
                .toList();
        return data;
    }

    private Path resolveConfigPath() {
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

