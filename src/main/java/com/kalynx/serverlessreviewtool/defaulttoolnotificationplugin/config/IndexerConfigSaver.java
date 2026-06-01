package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config;

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
 * <p>The file path is resolved via {@link IndexerConfigLoader#resolveConfigPath()} so
 * loader and saver always target the same file.
 */
public class IndexerConfigSaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerConfigSaver.class);

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final IndexerConfigLoader loader;

    public IndexerConfigSaver() {
        this(new IndexerConfigLoader());
    }

    IndexerConfigSaver(IndexerConfigLoader loader) {
        this.loader = loader;
    }

    /**
     * Persists {@code config} to disk.
     *
     * @param config the configuration to save
     * @throws IOException if the file cannot be written
     */
    public void save(IndexerConfig config) throws IOException {
        Path configPath = loader.resolveConfigPath();
        Files.writeString(configPath, gson.toJson(toJson(config)));
        LOGGER.info("Saved indexer configuration to: {}", configPath);
    }

    private JsonConfig toJson(IndexerConfig config) {
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
