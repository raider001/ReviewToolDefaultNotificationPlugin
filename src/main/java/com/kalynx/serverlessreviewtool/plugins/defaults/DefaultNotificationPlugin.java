package com.kalynx.serverlessreviewtool.plugins.defaults;

import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.RepositoryListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.RepositoryChangePoller;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfig;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfigLoader;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.RepositoriesFileWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Default notification plugin that detects repository changes via background polling.
 *
 * <p>This plugin provides stateless, in-memory polling of Git repositories without cloning.
 * It uses {@code git ls-remote} to efficiently detect when repository refs have changed,
 * firing notifications to registered listeners when updates occur.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Stateless - maintains only in-memory ref state, no file storage</li>
 *   <li>Background threads - runs daemon threads per repository, non-blocking</li>
 *   <li>Lightweight - uses git ls-remote only, no cloning or fetching</li>
 *   <li>Configurable - loads polling configuration from repositories.json</li>
 * </ul>
 *
 * <p><b>Configuration:</b>
 * Repositories to monitor are configured in {@code repositories.json} (or custom location via
 * system property {@code srt.notification.config}). Each repository entry includes:
 * <ul>
 *   <li>name - unique repository identifier</li>
 *   <li>url/location - remote URL or local path</li>
 *   <li>pollIntervalSeconds / poll_rate - milliseconds between polling</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * pluginManager.addListenerToNotificationPlugins(
 *     NotificationPlugin.NotificationType.REVIEW_UPDATED,
 *     updates -> System.out.println("Review updates received: " + updates.length)
 * );
 * }</pre>
 *
 * <p>The plugin is typically initialized by the application plugin manager,
 */
public class DefaultNotificationPlugin extends NotificationPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNotificationPlugin.class);
    private static final String CONFIG_PROPERTY = "srt.notification.config";
    private static final String DEFAULT_CONFIG_NAME = "repositories.json";

    private final Map<String, PollerConfig> trackedRepositories = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(
            10,
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("RepositoryChangePoller-" + scheduledTasks.size());
                return t;
            }
    );

    /**
     * Initializes polling from configured repositories and starts file watching.
     */
    @Override
    public void initialize() {
        LOGGER.info("DefaultNotificationPlugin initializing");
        PollerConfigLoader loader = new PollerConfigLoader();
        loader.loadConfigurations().forEach(this::startPolling);
        notifyRepositoriesUpdated();

        Path configPath = resolveConfigPath();
        RepositoriesFileWatcher watcher = new RepositoriesFileWatcher(
            configPath,
            this::onConfigurationChanged
        );
        watcher.start();
    }

    private void onConfigurationChanged(String newConfigContent) {
        LOGGER.info("Configuration changed, reloading repositories");
        PollerConfigLoader loader = new PollerConfigLoader();
        List<PollerConfig> newConfigs = loader.loadConfigurations();

        Map<String, PollerConfig> newConfigsByName = new java.util.HashMap<>();
        for (PollerConfig config : newConfigs) {
            newConfigsByName.put(config.repositoryName(), config);
        }

        for (String repoName : trackedRepositories.keySet()) {
            if (!newConfigsByName.containsKey(repoName)) {
                stopPolling(repoName);
                LOGGER.info("Removed repository from polling: {}", repoName);
            }
        }

        for (PollerConfig config : newConfigs) {
            if (!trackedRepositories.containsKey(config.repositoryName())) {
                startPolling(config);
                LOGGER.info("Added new repository to polling: {}", config.repositoryName());
            } else {
                PollerConfig existing = trackedRepositories.get(config.repositoryName());
                if (!existing.equals(config)) {
                    stopPolling(config.repositoryName());
                    startPolling(config);
                    LOGGER.info("Updated polling for repository: {}", config.repositoryName());
                }
            }
        }

        notifyRepositoriesUpdated();
    }

    private void stopPolling(String repositoryName) {
        ScheduledFuture<?> future = scheduledTasks.remove(repositoryName);
        if (future != null) {
            future.cancel(false);
        }
        trackedRepositories.remove(repositoryName);
    }

    private Path resolveConfigPath() {
        String configured = System.getProperty(CONFIG_PROPERTY, DEFAULT_CONFIG_NAME);
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private void startPolling(PollerConfig config) {
        trackedRepositories.put(config.repositoryName(), config);
        RepositoryChangePoller poller = new RepositoryChangePoller(config, this::onRepositoryChanged);
        ScheduledFuture<?> future = executorService.scheduleAtFixedRate(
                poller,
                0,
                config.pollIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        scheduledTasks.put(config.repositoryName(), future);
        LOGGER.info("Started polling for repository: {}", config.repositoryName());
    }

    private void onRepositoryChanged(PollerConfig config) {
        ReviewListUpdate update = new ReviewListUpdate(
            UUID.randomUUID().toString(),
            Instant.now(),
            ReviewUpdateType.UPDATED,
            config.repositoryName(),
            config.repositoryName(),
            List.of(config.repositoryName())
        );
        onReviewUpdated(update);
    }

    private void notifyRepositoriesUpdated() {
        RepositoryListUpdate update = new RepositoryListUpdate(
            UUID.randomUUID().toString(),
            Instant.now(),
            trackedRepositories.values().stream()
                .map(config -> new RepositoryDescriptor(
                    config.repositoryName(),
                    config.repositoryUrl()))
                .sorted(java.util.Comparator.comparing(RepositoryDescriptor::name))
                .toList()
        );
        onRepositoriesUpdated(update);
    }
}
