package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin;

import com.kalynx.lwdi.DependencyInjectionException;
import com.kalynx.lwdi.DependencyInjector;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfig;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfigManager;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientW;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientWrapper;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.requests.IndexerRestClient;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.sse.SseEventHandler;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.ui.RepositoriesManagementPanel;
import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.PluginPanel;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.List;

public class DefaultNotificationPlugin extends NotificationPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNotificationPlugin.class);

    private static final DependencyInjector DEPENDENCY_INJECTOR = new DependencyInjector();

    private final IndexerConfigManager configManager;
    private final IndexerRestClient indexerRestClient;
    private final SseEventHandler sseEventHandler;

    public DefaultNotificationPlugin() throws DependencyInjectionException {
        configManager = DEPENDENCY_INJECTOR.add(new IndexerConfigManager());
        DEPENDENCY_INJECTOR.add(HttpClientW.class, new HttpClientWrapper(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
        indexerRestClient = DEPENDENCY_INJECTOR.inject(IndexerRestClient.class);
        sseEventHandler = new SseEventHandler(this, configManager);
    }

    @Override
    public PluginPanel getUI() {
        return new PluginPanel("Indexer Configuration",
                new RepositoriesManagementPanel(configManager, this::onConfigurationChanged),
                50);
    }

    @Override
    public void initialize() {
        sseEventHandler.start();
        LOGGER.info("Plugin Initialised");
    }

    public void onConfigurationChanged(IndexerConfig newConfig) {
        sseEventHandler.stop();
        try {
            configManager.save(newConfig);
        } catch (Exception e) {
            LOGGER.error("Failed to save configuration: {}", e.getMessage(), e);
        }
        sseEventHandler.start();
        LOGGER.info("Configuration changed — SSE listener restarted");
    }

    @Override
    public List<ReviewSummary> getAllReviews() {
        return indexerRestClient.fetchReviews();
    }

    @Override
    public List<String> getAllBranches() {
        return indexerRestClient.fetchBranches();
    }

    @Override
    public List<RepositoryDescriptor> getAllRepositories() {
        return indexerRestClient.fetchRepositories();
    }

    @Override
    public List<String> getBranchesFromRepository(String repository) {
        return indexerRestClient.fetchBranchesFromRepository(repository);
    }
}
