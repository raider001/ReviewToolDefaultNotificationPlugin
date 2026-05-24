package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Maintains a persistent SSE connection to {@code GET /events/stream} on the Central Indexer.
 *
 * <p>When constructed with a specific repository name the listener tracks the last received
 * {@code id:} field as a cursor and sends it as {@code ?since=<cursor>} and the
 * {@code Last-Event-ID} header on reconnection so the server can replay missed events.
 *
 * <p>When constructed with the wildcard repository {@value #WILDCARD_REPO} the listener
 * subscribes to all repositories on a single connection. In that mode cursor tracking is
 * disabled; reconnections always use {@code since=0} and the server replays all stored
 * events within the retention window.
 *
 * <p>When the connection closes (server restart, network interruption) the listener waits
 * {@value #RECONNECT_DELAY_MS} ms before attempting to reconnect, repeating until
 * {@link #stop()} is called.
 *
 * <p>Designed to be submitted to a plain {@link Thread}; call {@link #stop()} and
 * interrupt the thread to shut it down cleanly.
 */
public class IndexerSseListener implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerSseListener.class);
    private static final long RECONNECT_DELAY_MS = 5_000L;
    public static final String WILDCARD_REPO = "*";

    private final String repository;
    private final IndexerConfig config;
    private final Consumer<IndexerEvent> onEvent;
    private final HttpClient http;

    private volatile boolean running = true;
    private long cursor = 0;

    /**
     * Creates an SSE listener for the given repository, or for all repositories when
     * {@code repository} is {@value #WILDCARD_REPO}.
     *
     * @param repository canonical repository ID, or {@value #WILDCARD_REPO} for all repositories
     * @param config     plugin configuration supplying the indexer URL and bearer token
     * @param onEvent    consumer called once per new event, in delivery order
     */
    public IndexerSseListener(String repository, IndexerConfig config, Consumer<IndexerEvent> onEvent) {
        this(repository, config, onEvent, HttpClient.newHttpClient());
    }

    IndexerSseListener(String repository, IndexerConfig config, Consumer<IndexerEvent> onEvent, HttpClient http) {
        this.repository = repository;
        this.config = config;
        this.onEvent = onEvent;
        this.http = http;
    }

    /**
     * Signals the listener to stop reconnecting after the current connection closes.
     * Interrupt the owning thread to unblock any in-progress network I/O immediately.
     */
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("SSE connection lost for '{}': {}", repository, e.getMessage());
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.info("SSE listener stopped for '{}'", repository);
    }

    private void connect() throws IOException, InterruptedException {
        String encodedRepo = URLEncoder.encode(repository, StandardCharsets.UTF_8);
        String url = config.indexerUrl() + "/events/stream?repository=" + encodedRepo + "&since=" + cursor;

        HttpRequest request = buildRequest(url);
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() == 410) {
            LOGGER.warn("Cursor for '{}' is outside the retention window; resetting to 0", repository);
            cursor = 0;
            return;
        }
        if (response.statusCode() != 200) {
            LOGGER.warn("Central Indexer returned {} for repository '{}'", response.statusCode(), repository);
            return;
        }

        LOGGER.info("SSE stream connected for '{}'", repository);
        parseSseStream(response.body());
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET();
        if (!config.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.bearerToken());
        }
        if (cursor > 0 && !WILDCARD_REPO.equals(repository)) {
            builder.header("Last-Event-ID", String.valueOf(cursor));
        }
        return builder.build();
    }

    private void parseSseStream(Stream<String> lines) {
        SseFrame frame = new SseFrame();
        lines.forEach(line -> {
            LOGGER.debug("SSE raw line for '{}': '{}'", repository, line);
            if (line.isEmpty()) {
                if (frame.hasData()) {
                    dispatchFrame(frame);
                }
                frame.reset();
            } else if (line.startsWith("id: ")) {
                frame.id = line.substring(4).trim();
            } else if (line.startsWith("event: ")) {
                frame.eventType = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
                frame.data = line.substring(6).trim();
            }
        });
    }

    private void dispatchFrame(SseFrame frame) {
        if (frame.id != null) {
            try {
                cursor = Long.parseLong(frame.id);
            } catch (NumberFormatException ignored) {
            }
        }
        LOGGER.info("[Plugin-SSE] Frame received for '{}': event='{}' id='{}'",
                repository, frame.eventType, frame.id);
        IndexerEvent event = parseEvent(frame);
        if (event != null) {
            LOGGER.info("[Plugin-SSE] Dispatching to plugin: type='{}' repo='{}' reviewId='{}' repoUrl='{}'",
                    event.eventType(), event.repository(), event.reviewId(), event.repositoryUrl());
            onEvent.accept(event);
        } else {
            LOGGER.info("[Plugin-SSE] Frame parsed to null event (unrecognised or malformed)");
        }
    }

    private IndexerEvent parseEvent(SseFrame frame) {
        if (frame.data == null) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(frame.data).getAsJsonObject();
            String eventType = frame.eventType != null ? frame.eventType
                    : (obj.has("type") ? obj.get("type").getAsString() : null);
            if (eventType == null) {
                return null;
            }
            String reviewId = getString(obj, "reviewId");
            String repo = getString(obj, "repository");
            JsonObject payload = obj.has("payload") && obj.get("payload").isJsonObject()
                    ? obj.getAsJsonObject("payload") : new JsonObject();
            String repositoryUrl = getString(payload, "repository_url");
            String branchName = getString(payload, "branch_name");
            return new IndexerEvent(eventType, reviewId, repo, repositoryUrl, branchName);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse SSE event for '{}': {}", repository, e.getMessage());
            return null;
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static class SseFrame {
        String id;
        String eventType;
        String data;

        boolean hasData() {
            return data != null;
        }

        void reset() {
            id = null;
            eventType = null;
            data = null;
        }
    }

    /**
     * A single event received from the Central Indexer SSE stream.
     *
     * @param eventType     event type string (e.g. {@code "review.created"}, {@code "branch.updated"})
     * @param reviewId      review identifier, or {@code null} if not present in the payload
     * @param repository    canonical repository identifier ({@code "owner/repo"}), or {@code null}
     * @param repositoryUrl canonical git URL for the repository; present on {@code branch.*} events
     * @param branchName    branch name; present on {@code branch.*} events
     */
    public record IndexerEvent(
            String eventType,
            String reviewId,
            String repository,
            String repositoryUrl,
            String branchName) {}
}

