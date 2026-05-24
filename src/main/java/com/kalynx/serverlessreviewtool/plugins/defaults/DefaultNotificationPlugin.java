package com.kalynx.serverlessreviewtool.plugins.defaults;

import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.PluginPanel;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.RepositoryListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfig;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfigLoader;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfigSaver;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerRestClient;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerSseListener;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.ui.RepositoriesManagementPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default notification plugin that receives review change events from the Central Indexer.
 *
 * <p>On initialisation the plugin:
 * <ol>
 *   <li>Calls {@code GET /reviews} to fetch the current review list and fires a
 *       {@code CREATED} event for every review so the application populates its initial state.</li>
 *   <li>Opens a single persistent SSE connection using {@code GET /events/stream?repository=*}
 *       to receive incremental updates thereafter.</li>
 * </ol>
 *
 * <p>The {@link IndexerSseListener} reconnects automatically after a network interruption.
 * When the user saves changes in the settings panel, the plugin restarts its SSE listener
 * and re-fetches the review list with the updated configuration.
 *
 * <p>Configuration format ({@code repositories.json}):
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
public class DefaultNotificationPlugin extends NotificationPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNotificationPlugin.class);

    private final Map<String, ListenerHandle> handles = new ConcurrentHashMap<>();

    @Override
    public PluginPanel getUI() {
        RepositoriesManagementPanel panel = new RepositoriesManagementPanel(
                new IndexerConfigLoader(),
                new IndexerConfigSaver(),
                this::onConfigurationChanged);
        return new PluginPanel("Repositories", panel, 50);
    }

    @Override
    public void initialize() {
        LOGGER.info("DefaultNotificationPlugin initializing");
        IndexerConfig config = new IndexerConfigLoader().load();
        List<IndexerRestClient.ReviewSummary> reviews = fetchInitialReviews(config);
        fireInitialReviewEvents(reviews, config);
        notifyRepositoriesUpdated(config, reviews);
        startListeners(config);
    }

    private void onConfigurationChanged() {
        LOGGER.info("Configuration changed — restarting SSE listeners");
        stopAllListeners();
        IndexerConfig config = new IndexerConfigLoader().load();
        List<IndexerRestClient.ReviewSummary> reviews = fetchInitialReviews(config);
        fireInitialReviewEvents(reviews, config);
        notifyRepositoriesUpdated(config, reviews);
        startListeners(config);
    }

    private List<IndexerRestClient.ReviewSummary> fetchInitialReviews(IndexerConfig config) {
        if (config.indexerUrl().isBlank()) {
            return List.of();
        }
        List<IndexerRestClient.ReviewSummary> reviews = new IndexerRestClient().fetchReviews(config);
        LOGGER.info("Fetched {} reviews from indexer at startup", reviews.size());
        return reviews;
    }

    private void fireInitialReviewEvents(List<IndexerRestClient.ReviewSummary> reviews, IndexerConfig config) {
        for (IndexerRestClient.ReviewSummary summary : reviews) {
            String primaryRepo = summary.repositories().isEmpty()
                    ? null
                    : summary.repositories().getFirst().repository();
            String repoUrl = resolveRepositoryUrl(primaryRepo, summary.repositories(), config);
            List<String> repoNames = summary.repositories().stream()
                    .map(IndexerRestClient.RepositoryRef::repository)
                    .collect(Collectors.toList());

            ReviewUpdateType type = "COMPLETED".equalsIgnoreCase(summary.status())
                    || "CANCELLED".equalsIgnoreCase(summary.status())
                    ? ReviewUpdateType.UPDATED
                    : ReviewUpdateType.CREATED;

            ReviewListUpdate update = new ReviewListUpdate(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    type,
                    summary.reviewId(),
                    primaryRepo,
                    repoNames,
                    repoUrl,
                    null);
            onReviewUpdated(update);
        }
    }

    private void startListeners(IndexerConfig config) {
        if (config.indexerUrl().isBlank()) {
            LOGGER.info("No indexer URL configured — SSE listener not started");
            return;
        }
        startListener(IndexerSseListener.WILDCARD_REPO, config);
    }

    private void startListener(String repository, IndexerConfig config) {
        IndexerSseListener listener = new IndexerSseListener(repository, config, this::onIndexerEvent);
        Thread thread = new Thread(listener, "IndexerSseListener-" + repository);
        thread.setDaemon(true);
        thread.start();
        handles.put(repository, new ListenerHandle(listener, thread));
        LOGGER.info("Started SSE listener for '{}'", repository);
    }

    private void stopAllListeners() {
        handles.values().forEach(ListenerHandle::stop);
        handles.clear();
    }

    private void onIndexerEvent(IndexerSseListener.IndexerEvent event) {
        ReviewUpdateType type = mapEventType(event.eventType());
        if (type == null) {
            LOGGER.debug("Ignoring unrecognised event type '{}'", event.eventType());
            return;
        }
        String eventId = UUID.randomUUID().toString();

        // For branch.* events the URL is in the payload; for review.* events look it up from config.
        IndexerConfig config = new IndexerConfigLoader().load();
        String repoUrl = event.repositoryUrl() != null
                ? event.repositoryUrl()
                : resolveUrlFromConfig(event.repository(), config);

        List<String> repos = event.repository() != null ? List.of(event.repository()) : List.of();

        ReviewListUpdate update = new ReviewListUpdate(
                eventId,
                Instant.now(),
                type,
                event.reviewId(),
                event.repository(),
                repos,
                repoUrl,
                event.branchName());
        onReviewUpdated(update);
    }

    private ReviewUpdateType mapEventType(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "REVIEW_CREATED"                                                  -> ReviewUpdateType.CREATED;
            case "REVIEW_UPDATED", "REVIEW_CLOSED",
                 "REVIEW_COMMENT_ADDED", "REVIEW_COMMENT_UPDATED"                 -> ReviewUpdateType.UPDATED;
            case "BRANCH_UPDATED"                                                  -> ReviewUpdateType.UPDATED;
            case "BRANCH_DELETED"                                                  -> ReviewUpdateType.DELETED;
            default                                                                -> null;
        };
    }

    /**
     * Resolves the git URL for a repository name.
     * Checks the SSE-event-supplied list first, then falls back to plugin config.
     */
    private String resolveRepositoryUrl(String repoName,
                                        List<IndexerRestClient.RepositoryRef> payloadRefs,
                                        IndexerConfig config) {
        if (repoName == null) return null;
        // Try the payload refs first (populated from GET /reviews response).
        for (IndexerRestClient.RepositoryRef ref : payloadRefs) {
            if (repoName.equals(ref.repository()) && ref.repositoryUrl() != null) {
                return ref.repositoryUrl();
            }
        }
        return resolveUrlFromConfig(repoName, config);
    }

    private String resolveUrlFromConfig(String repoName, IndexerConfig config) {
        if (repoName == null) return null;
        return config.repositories().stream()
                .filter(r -> repoName.equals(r.name()))
                .map(IndexerConfig.RepositoryEntry::location)
                .findFirst()
                .orElse(null);
    }

    private void notifyRepositoriesUpdated(IndexerConfig config,
                                           List<IndexerRestClient.ReviewSummary> reviews) {
        // Merge repositories from config and from the review list (reviews may reference repos
        // not yet in the local config).
        List<RepositoryDescriptor> descriptors = new ArrayList<>();
        config.repositories().stream()
                .map(r -> new RepositoryDescriptor(r.name(), r.location()))
                .forEach(descriptors::add);

        for (IndexerRestClient.ReviewSummary summary : reviews) {
            for (IndexerRestClient.RepositoryRef ref : summary.repositories()) {
                boolean known = descriptors.stream()
                        .anyMatch(d -> d.name().equals(ref.repository()));
                if (!known && ref.repository() != null) {
                    descriptors.add(new RepositoryDescriptor(ref.repository(), ref.repositoryUrl()));
                }
            }
        }

        descriptors.sort(Comparator.comparing(RepositoryDescriptor::name));
        RepositoryListUpdate update = new RepositoryListUpdate(
                UUID.randomUUID().toString(),
                Instant.now(),
                descriptors);
        onRepositoriesUpdated(update);
    }

    private record ListenerHandle(IndexerSseListener listener, Thread thread) {
        void stop() {
            listener.stop();
            thread.interrupt();
        }
    }
}
