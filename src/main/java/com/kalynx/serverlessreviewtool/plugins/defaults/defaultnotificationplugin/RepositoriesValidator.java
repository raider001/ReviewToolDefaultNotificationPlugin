package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Validates repositories.json configuration files.
 * Ensures JSON is well-formed and contains valid repository entries.
 */
public class RepositoriesValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoriesValidator.class);
    private final Gson gson = new Gson();

    /**
     * Validates that the content can be parsed and contains valid repository data.
     *
     * @param content JSON content to validate
     * @return true if valid, false otherwise
     */
    public boolean validate(String content) {
        try {
            RepositoriesConfig config = gson.fromJson(content, RepositoriesConfig.class);
            if (config == null || config.repositories == null) {
                LOGGER.warn("Configuration is missing or has no repositories array");
                return false;
            }
            if (config.repositories.isEmpty()) {
                LOGGER.warn("Configuration has empty repositories array");
                return false;
            }
            for (RepositoryEntry repo : config.repositories) {
                if (repo.name == null || repo.name.trim().isEmpty()) {
                    LOGGER.warn("Repository entry is missing or has empty name");
                    return false;
                }
                String repoUrl = repo.url != null && !repo.url.isEmpty() ? repo.url : repo.location;
                if (repoUrl == null || repoUrl.trim().isEmpty()) {
                    LOGGER.warn("Repository '{}' has no url or location", repo.name);
                    return false;
                }
            }
            return true;
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Invalid JSON syntax: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.warn("Validation error: {}", e.getMessage());
            return false;
        }
    }

    private static class RepositoriesConfig {
        List<RepositoryEntry> repositories;
    }

    private static class RepositoryEntry {
        String name;
        String url;
        String location;
        long poll_rate = 60000;
    }
}

