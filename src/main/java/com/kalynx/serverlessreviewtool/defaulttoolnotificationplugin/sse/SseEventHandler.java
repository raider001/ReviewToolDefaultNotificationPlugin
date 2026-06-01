package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.sse;

import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfigManager;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientW;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientWrapper;
import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.BranchIndex;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.CommentIndex;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.ReviewUpdateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * State machine and handler for Central Indexer SSE events.
 *
 * <p>{@link #handle(SseEvent)} is the state machine entry point. It resolves the raw
 * event type string to a canonical {@link IndexerEventType} and dispatches to the
 * corresponding private handler method, which constructs the typed payload and calls
 * the appropriate method on the {@link NotificationPlugin}.
 *
 * <p>{@link #start()} owns the {@link IndexerSseListener} thread lifecycle. Config is
 * read from the {@link IndexerConfigManager} at start time so a restart always picks up
 * the latest settings.
 */
public class SseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseEventHandler.class);

    private final NotificationPlugin notificationPlugin;
    private final IndexerConfigManager configManager;
    private final HttpClientW httpClient;

    private final Map<IndexerEventType, Consumer<SseEvent>> handlers;

    private volatile IndexerSseListener listener;
    private volatile Thread listenerThread;

    public SseEventHandler(NotificationPlugin notificationPlugin, IndexerConfigManager configManager) {
        this(notificationPlugin, configManager, new HttpClientWrapper(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
    }

    SseEventHandler(NotificationPlugin notificationPlugin, IndexerConfigManager configManager, HttpClientW httpClient) {
        this.notificationPlugin = notificationPlugin;
        this.configManager = configManager;
        this.httpClient = httpClient;
        this.handlers = buildHandlers();
    }

    private Map<IndexerEventType, Consumer<SseEvent>> buildHandlers() {
        Map<IndexerEventType, Consumer<SseEvent>> map = new HashMap<>();
        map.put(IndexerEventType.REVIEW_CREATED,  this::handleOnReviewCreated);
        map.put(IndexerEventType.REVIEW_UPDATED,  this::handleOnReviewUpdated);
        map.put(IndexerEventType.BRANCH_UPDATED,  this::handleOnBranchUpdated);
        map.put(IndexerEventType.BRANCH_DELETED,  this::handleOnBranchDeleted);
        map.put(IndexerEventType.COMMENT_ADDED,   this::handleOnCommentAdded);
        map.put(IndexerEventType.COMMENT_UPDATED, this::handleOnCommentUpdated);
        return map;
    }

    public void start() {
        String url = configManager.get().indexerUrl();
        if (url == null || url.isBlank()) {
            LOGGER.info("No indexer URL configured — SSE listener not started");
            return;
        }
        listener = new IndexerSseListener(
                IndexerSseListener.WILDCARD_REPO, configManager, this::handle, httpClient);
        listenerThread = new Thread(listener, "IndexerSseListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        LOGGER.info("SSE event handler started for {}", url);
    }

    public void stop() {
        IndexerSseListener l = listener;
        Thread t = listenerThread;
        if (l != null) l.stop();
        if (t != null) t.interrupt();
        listener = null;
        listenerThread = null;
    }

    /**
     * State machine entry point. Resolves the raw event type to a canonical
     * {@link IndexerEventType} and dispatches to the appropriate handler.
     */
    void handle(SseEvent event) {
        IndexerEventType type = IndexerEventType.from(event.eventType());
        Consumer<SseEvent> handler = handlers.get(type);
        if (handler != null) {
            handler.accept(event);
        } else {
            LOGGER.debug("No handler for event type '{}' — ignored", event.eventType());
        }
    }

    private void handleOnReviewCreated(SseEvent event) {
        LOGGER.info("Received review created event for {}", event.reviewId());
        notificationPlugin.onReviewCreated(toReviewListUpdate(event, ReviewUpdateType.CREATED));
    }

    private void handleOnReviewUpdated(SseEvent event) {
        LOGGER.info("Received review updated event for {}", event.reviewId());
        notificationPlugin.onReviewUpdated(toReviewListUpdate(event, ReviewUpdateType.UPDATED));
    }

    private void handleOnBranchUpdated(SseEvent event) {
        LOGGER.info("Received branch updated event for {}", event.branchName());
        notificationPlugin.onBranchUpdated(new BranchIndex(event.branchName(), event.repositoryUrl()));
    }

    private void handleOnBranchDeleted(SseEvent event) {
        LOGGER.info("Received branch deleted event for {}", event.branchName());
        notificationPlugin.onBranchDeleted(new BranchIndex(event.branchName(), event.repositoryUrl()));
    }

    private void handleOnCommentAdded(SseEvent event) {
        LOGGER.info("Received comment added event for {}", event.commentId());
        notificationPlugin.onCommentAdded(new CommentIndex(event.reviewId(), event.commentId(), event.repositoryUrl()));
    }

    private void handleOnCommentUpdated(SseEvent event) {
        LOGGER.info("Received comment updated event for {}", event.commentId());
        notificationPlugin.onCommentUpdated(new CommentIndex(event.reviewId(), event.commentId(), event.repositoryUrl()));
    }

    private static ReviewListUpdate toReviewListUpdate(SseEvent event, ReviewUpdateType type) {
        LOGGER.debug("Converting SSE event to ReviewListUpdate: {}", event);
        List<String> repos = event.repository() != null ? List.of(event.repository()) : List.of();
        return new ReviewListUpdate(
                UUID.randomUUID().toString(), Instant.now(), type,
                event.reviewId(), event.repository(), repos,
                event.repositoryUrl(), event.branchName());
    }
}