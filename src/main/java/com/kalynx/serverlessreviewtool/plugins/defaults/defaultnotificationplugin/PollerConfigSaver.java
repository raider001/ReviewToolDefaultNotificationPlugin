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
 * Saves polling configuration to the repositories.json file.
 * Writes in the standard format expected by {@link PollerConfigLoader}.
 */
public class PollerConfigSaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PollerConfigSaver.class);
    private static final String CONFIG_PROPERTY = "srt.notification.config";
    private static final String DEFAULT_CONFIG_NAME = "repositories.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Persists the given list of {@link PollerConfig} entries to the configuration file.
     *
     * @param configs the repositories to save
     * @throws IOException if the file cannot be written
     */
    public void save(List<PollerConfig> configs) throws IOException {
        Path configPath = resolveConfigPath();
        RepositoriesConfig data = new RepositoriesConfig();
        data.repositories = configs.stream()
            .map(this::toEntry)
            .toList();
        Files.writeString(configPath, gson.toJson(data));
        LOGGER.info("Saved {} repository configurations to: {}", configs.size(), configPath);
    }

    private RepositoryEntry toEntry(PollerConfig config) {
        RepositoryEntry entry = new RepositoryEntry();
        entry.name = config.repositoryName();
        entry.location = config.repositoryUrl();
        entry.poll_rate = config.pollIntervalMs();
        return entry;
    }

    private Path resolveConfigPath() {
        String configured = System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_NAME);
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static class RepositoriesConfig {
        List<RepositoryEntry> repositories;
    }

    private static class RepositoryEntry {
        String name;
        String location;
        long poll_rate;
    }
}

