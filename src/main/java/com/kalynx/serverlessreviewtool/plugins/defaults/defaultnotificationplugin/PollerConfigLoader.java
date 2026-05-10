package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
/**
 * Loads polling configuration from repositories.json file.
 */
public class PollerConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(PollerConfigLoader.class);
    private static final String CONFIG_PROPERTY = "srt.notification.config";
    private static final String DEFAULT_CONFIG_NAME = "repositories.json";
    private final Gson gson = new Gson();
    /**
     * Loads polling configurations from the configuration file.
     * The file path is determined by system property srt.notification.config,
     * or defaults to repositories.json in the current directory.
     *
     * @return list of polling configurations
     */
    public List<PollerConfig> loadConfigurations() {
        Path configPath = resolveConfigPath();
        if (!Files.exists(configPath)) {
            LOGGER.warn("Configuration file not found: {}", configPath);
            return List.of();
        }
        try {
            String content = Files.readString(configPath);
            RepositoriesConfig config = gson.fromJson(content, RepositoriesConfig.class);
            if (config == null || config.repositories == null) {
                LOGGER.warn("Invalid configuration file format: {}", configPath);
                return List.of();
            }
            List<PollerConfig> pollerConfigs = new ArrayList<>();
            for (RepositoryEntry repo : config.repositories) {
                if (repo.name == null || repo.name.isEmpty()) {
                    continue;
                }

                String repoUrl = repo.url != null && !repo.url.isEmpty() ? repo.url : repo.location;
                if (repoUrl == null || repoUrl.isEmpty()) {
                    LOGGER.warn("Repository {} has no url or location, skipping", repo.name);
                    continue;
                }
                repoUrl = normalizeRepositoryUrl(repoUrl);
                if (repoUrl.contains("${")) {
                    LOGGER.warn("Repository {} has unresolved placeholders in url/location '{}', skipping", repo.name, repoUrl);
                    continue;
                }

                long pollIntervalMs = determinePollInterval(repo);
                pollerConfigs.add(new PollerConfig(repo.name, repoUrl, pollIntervalMs));
            }
            LOGGER.info("Loaded {} repository configurations from: {}", pollerConfigs.size(), configPath);
            return pollerConfigs;
        } catch (JsonSyntaxException e) {
            LOGGER.error("Invalid JSON in configuration file: {}", configPath, e);
            return List.of();
        } catch (IOException e) {
            LOGGER.error("Error reading configuration file: {}", configPath, e);
            return List.of();
        }
    }
    private Path resolveConfigPath() {
        String configured = System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_NAME);
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private long determinePollInterval(RepositoryEntry repo) {
        if (repo.poll_rate > 0) {
            return repo.poll_rate;
        }
        if (repo.pollIntervalSeconds > 0) {
            return repo.pollIntervalSeconds * 1000L;
        }
        return 60000L;
    }

    private String normalizeRepositoryUrl(String rawUrl) {
        String normalized = rawUrl.trim();
        String userHome = System.getProperty("user.home");
        normalized = normalized.replace("${user.home}", userHome);
        if (normalized.startsWith("~/")) {
            normalized = userHome + normalized.substring(1);
        }

        if (normalized.startsWith("file://")) {
            String localPart = normalized.substring("file://".length());
            if (localPart.startsWith("/") && localPart.length() > 2
                && Character.isLetter(localPart.charAt(1)) && localPart.charAt(2) == ':') {
                localPart = localPart.substring(1);
            }
            Path localPath = Path.of(localPart).toAbsolutePath().normalize();
            return localPath.toUri().toString();
        }

        return normalized;
    }

    private static class RepositoriesConfig {
        List<RepositoryEntry> repositories;
    }

    private static class RepositoryEntry {
        String name;
        String url;
        String location;
        int pollIntervalSeconds = 60;
        long poll_rate = 0;
    }
}
